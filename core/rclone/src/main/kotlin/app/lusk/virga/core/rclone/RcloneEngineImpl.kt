package app.lusk.virga.core.rclone

import android.util.Log
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteProvider
import app.lusk.virga.core.common.model.RemoteQuota
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.rclone.api.RcApiClient
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import app.lusk.virga.core.rclone.daemon.RcloneDaemonManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [RcloneEngine] backed by an rclone RC daemon. Maintains a single
 * long-lived daemon, reused across calls and started on demand.
 */
@Singleton
class RcloneEngineImpl @Inject constructor(
    private val daemonManager: RcloneDaemonManager,
    private val configManager: RcloneConfigManager,
    private val apiClient: RcApiClient,
    private val dispatchers: DispatcherProvider,
) : RcloneEngine {

    private val lock = Mutex()
    @Volatile private var daemon: RcloneDaemon? = null

    // Reference count of active long-lived consumers (syncs, dry-run, file browser).
    // The daemon is torn down only when the last lease is released, so one sync's
    // completion can't kill a daemon another concurrent sync is still using.
    // Guarded by [lock].
    private var leases = 0

    // Set when a failed/cancelled daemon-OAuth left a token-less/partial remote in the
    // daemon's WORKING config but a concurrent sync still co-leases the daemon, so the
    // OAuth path can't tear it down to discard. The eventual last-leaseholder teardown
    // honours this and discards (cleanup) instead of persisting the tainted config.
    // Guarded by [lock]; no valid config mutation can interleave (mutations are refused
    // while leased), so discarding is always correct once set.
    private var discardWorkingConfig = false

    /**
     * Finalize the daemon's working config on teardown (call under [lock] with the
     * daemon already stopped): re-encrypt+persist normally, or discard it if a failed
     * OAuth tainted it. Clears the discard flag.
     */
    private suspend fun finalizeConfigLocked() {
        if (discardWorkingConfig) {
            runCatching { configManager.cleanup() }
            discardWorkingConfig = false
        } else {
            configManager.persistAndCleanup()
        }
    }

    override suspend fun startDaemon(): RcloneDaemon = lock.withLock { ensureDaemonLocked() }

    override suspend fun acquireDaemon(): RcloneDaemon = lock.withLock {
        val d = ensureDaemonLocked()  // increment only after a successful start
        leases++
        d
    }

    override suspend fun releaseDaemon() = withContext(NonCancellable) {
        // NonCancellable around the WHOLE critical section (lock acquisition included):
        // a cancelled caller (VM scope dies, WorkManager stop) must still decrement the
        // lease, tear the daemon down, and re-encrypt the plaintext config — or it leaks
        // the rclone process, leaves OAuth tokens in plaintext on disk, and (if lock()
        // were cancelled mid-decrement) leaks the lease, wedging all future mutations.
        lock.withLock {
            if (leases > 0) leases--
            if (leases == 0) {
                daemon?.let { daemonManager.stop(it) }
                daemon = null
                finalizeConfigLocked()
            }
        }
    }

    override suspend fun stopDaemonIfIdle() = withContext(NonCancellable) {
        // NonCancellable: teardown must complete even if the caller's job is cancelled
        // (otherwise the rclone process + plaintext config survive).
        lock.withLock {
            // Don't disturb a daemon a leased consumer (an active sync) is using.
            if (leases == 0) {
                daemon?.let { daemonManager.stop(it) }
                daemon = null
                finalizeConfigLocked()
            }
        }
    }

    override suspend fun cleanupStaleConfigIfIdle() = lock.withLock {
        // Purge plaintext orphaned by a killed worker. Lease-aware: only when idle, so
        // a concurrently-launched worker's in-use config is never deleted.
        if (leases == 0 && daemon == null) configManager.cleanup()
    }

    /**
     * Start-or-reuse the daemon. MUST be called while holding [lock]: the
     * check-then-act on [daemon] (read health, decrypt config, start, assign)
     * has to be atomic, or two callers can each start a daemon, or one can read
     * a daemon another is concurrently tearing down.
     */
    private suspend fun ensureDaemonLocked(): RcloneDaemon {
        daemon?.takeIf { daemonManager.isAlive(it) }?.let { return it }
        val configFile = configManager.decryptForDaemon()
        return try {
            daemonManager.start(configFile).also { daemon = it }
        } catch (t: Throwable) {
            // Don't leave the decrypted plaintext config on disk if startup failed.
            runCatching { configManager.cleanup() }
            throw t
        }
    }

    override suspend fun stopDaemon() = withContext(NonCancellable) {
        // NonCancellable: teardown must complete even if the caller's job is cancelled
        // (otherwise the rclone process + plaintext config survive).
        lock.withLock {
            daemon?.let { daemonManager.stop(it) }
            daemon = null
            finalizeConfigLocked()
        }
    }

    override suspend fun isDaemonHealthy(): Boolean {
        val d = daemon ?: return false
        if (!daemonManager.isAlive(d)) return false
        return try {
            rc(d, "rc/noop")
            true
        } catch (e: CancellationException) {
            // runCatching would have swallowed cancellation and reported "unhealthy";
            // a cancelled caller must propagate, not be told the daemon is dead.
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Calls `config/providers` and maps it via [parseProviders]. Returns an empty
     * list on any failure — the UI falls back to the freeform textarea.
     */
    override suspend fun providers(): List<RemoteProvider> = runCatching {
        withLease { d -> parseProviders(rc(d, "config/providers")) }
    }.getOrElse {
        // Degrade to the freeform textarea (documented UX); log so a persistently failing daemon isn't invisible.
        // The runCatching wraps withLease so a failed RC call still releases the lease (tearing the daemon
        // down when idle) before we fall back.
        Log.w(TAG, "config/providers failed; falling back to freeform remote entry", it)
        emptyList()
    }

    override suspend fun listRemotes(): List<Remote> {
        val config = getConfig()
        return config.remotes.map { (name, type) -> Remote(name, type) }
    }

    override suspend fun getConfig(): RcloneConfig = withLease { d ->
        val dump = rc(d, "config/dump")
        val remotes = dump.entries.associate { (name, value) ->
            name to (value.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "unknown")
        }
        RcloneConfig(remotes)
    }

    override suspend fun createRemote(
        name: String,
        type: String,
        params: Map<String, String>,
        sensitiveKeys: Set<String>,
    ) {
        mutatingConfig { d ->
            rc(d, "config/create", buildJsonObject {
                put("name", name)
                put("type", type)
                putJsonObject("parameters") { params.forEach { (k, v) -> put(k, v) } }
                putJsonObject("opt") {
                    put("nonInteractive", true)
                    if (sensitiveKeys.isNotEmpty()) put("obscure", true)
                }
            })
        }
    }

    /**
     * Creates a crypt remote via rclone's `config/create` RC endpoint.
     *
     * Security contract: plaintext [password] / [salt] values are placed directly
     * into the RC request with `opt.obscure = true`. rclone's daemon handles the
     * obscuring transformation before writing to its config file — this app never
     * stores, logs, or otherwise persists any plaintext password material. The
     * values exist in memory only for the duration of this call.
     *
     * RC parameters used (documented at https://rclone.org/crypt/):
     *   - `remote`    : the base remote spec, e.g. "gdrive:encrypted"
     *   - `password`  : plaintext password; rclone obscures it when opt.obscure=true
     *   - `password2` : optional salt/second password; same treatment
     *   - `opt.nonInteractive` : prevents rclone from opening its own browser flow
     *   - `opt.obscure`        : instructs rclone to obscure any plaintext passwords
     */
    override suspend fun createCryptRemote(
        name: String,
        baseRemoteSpec: String,
        password: String,
        salt: String?,
    ) {
        mutatingConfig { d ->
            rc(d, "config/create", buildJsonObject {
                put("name", name)
                put("type", "crypt")
                putJsonObject("parameters") {
                    put(KEY_REMOTE, baseRemoteSpec)
                    put("password", password)
                    if (!salt.isNullOrBlank()) put("password2", salt)
                }
                putJsonObject("opt") {
                    put("nonInteractive", true)
                    // Tell rclone to obscure plaintext passwords before persisting.
                    // Without this, rclone would store the password in plaintext in
                    // the config file. With it, rclone applies its own reversible
                    // obscuring — this app never needs to implement that algorithm.
                    put("obscure", true)
                }
            })
        }
    }

    override suspend fun deleteRemote(name: String) {
        mutatingConfig { d ->
            rc(d, "config/delete", buildJsonObject { put("name", name) })
        }
    }

    /**
     * Renames [oldName] → [newName] inside one exclusive config section:
     * 1. Reads existing params via config/get.
     * 2. Creates the new remote via config/create.
     * 3. Deletes the old remote via config/delete.
     *
     * IMPORTANT: the values from config/get are already in rclone's obscured form.
     * We pass them to config/create WITHOUT opt.obscure so rclone writes them as-is.
     * Setting obscure=true would re-obscure already-obscured values and corrupt passwords.
     *
     * If config/create fails, the old remote is untouched (safe). Does NOT touch the DB.
     */
    override suspend fun renameRemote(oldName: String, newName: String) {
        mutatingConfig { d ->
            val current = rc(d, "config/get", buildJsonObject { put("name", oldName) })
            val type = current["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (type.isBlank()) {
                throw VirgaError.Rclone(
                    message = "Cannot rename \"$oldName\": remote not found or has no type.",
                )
            }
            // Build params: all primitive values except "type" (type is a top-level field).
            val params = current.entries.mapNotNull { (k, v) ->
                if (k == "type") null
                else {
                    val str = runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()
                    if (str != null) k to str else null
                }
            }.toMap()
            // Create the new remote first — old is untouched if this fails.
            rc(d, "config/create", buildJsonObject {
                put("name", newName)
                put("type", type)
                putJsonObject("parameters") { params.forEach { (k, v) -> put(k, v) } }
                putJsonObject("opt") {
                    put("nonInteractive", true)
                    // DO NOT set obscure=true: the values from config/get are already in
                    // rclone's obscured form. Re-obscuring would corrupt the passwords.
                }
            })
            rc(d, "config/delete", buildJsonObject { put("name", oldName) })
        }
    }

    override suspend fun updateRemote(
        name: String,
        params: Map<String, String>,
        sensitiveKeys: Set<String>,
    ) {
        mutatingConfig { d ->
            rc(d, "config/update", buildJsonObject {
                put("name", name)
                putJsonObject("parameters") { params.forEach { (k, v) -> put(k, v) } }
                putJsonObject("opt") {
                    put("nonInteractive", true)
                    if (sensitiveKeys.isNotEmpty()) put("obscure", true)
                }
            })
        }
    }

    override suspend fun getRemoteParams(name: String): Map<String, String> =
        withLease { d ->
            val result = rc(d, "config/get", buildJsonObject { put("name", name) })
            result.entries.mapNotNull { (k, v) ->
                val str = runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()
                if (str != null) k to str else null
            }.toMap()
        }

    override suspend fun importConfig(confContent: String) = lock.withLock {
        // Atomic under [lock]: stop the daemon and replace the config without a gap
        // where a concurrent ensureDaemon could start with the OLD config and then
        // clobber the import on its next persist.
        //
        // Refuse to mutate while a sync holds a lease — stopping the daemon here would
        // kill the in-flight transfer. The UI surfaces this message.
        if (leases > 0) {
            throw VirgaError.Rclone(message = "Stop running syncs before importing a config.")
        }
        // Validate-then-commit: user-supplied bytes (storage-picker file = trust
        // boundary). Snapshot current config as raw CIPHERTEXT (no plaintext in memory)
        // for rollback, then write the import and start the daemon to validate.
        val snapshot = configManager.snapshotCiphertext()
        daemon?.let { daemonManager.stop(it) }
        daemon = null
        configManager.cleanup()
        configManager.import(confContent)

        // Dump to validate. CRUCIAL: distinguish "parsed zero remotes" (bad file) from a
        // transient daemon/RC failure — collapsing both to "invalid" would roll back a
        // perfectly good import. Tear the validation daemon down + discard plaintext after.
        val dump = runCatching { rc(ensureDaemonLocked(), "config/dump") }
        daemon?.let { daemonManager.stop(it) }
        daemon = null
        configManager.cleanup()

        val remotes = dump.getOrElse { e ->
            Log.w(TAG, "Import validation could not start the daemon / dump config", e)
            configManager.restoreCiphertext(snapshot)
            throw VirgaError.Rclone(
                message = "Couldn't validate the imported config — the sync engine failed to start. " +
                    "Your previous config was kept. Try again.",
            )
        }
        if (remotes.isEmpty()) {
            configManager.restoreCiphertext(snapshot)
            throw VirgaError.Rclone(
                message = "Imported file is not a valid rclone config (no remotes found).",
            )
        }
    }

    override suspend fun listDir(
        remote: String,
        path: String,
        recurse: Boolean,
        filters: List<String>,
    ): List<FileItem> = withLease { d ->
        val result = rc(d, "operations/list", buildJsonObject {
            put("fs", remote)
            put(KEY_REMOTE, path)
            if (recurse) putJsonObject("opt") { put("recurse", true) }
            putFilters(filters)
        })
        val list = result["list"]?.jsonArray ?: return@withLease emptyList()
        list.map { it.jsonObject }.map { obj ->
            val modTimeMs = obj["ModTime"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            }
            FileItem(
                name = obj["Name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                path = obj["Path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                isDir = obj["IsDir"]?.jsonPrimitive?.booleanOrNull ?: false,
                size = obj["Size"]?.jsonPrimitive?.longOrNull ?: 0L,
                modTimeEpochMs = modTimeMs,
                mimeType = obj["MimeType"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    override fun sync(
        source: String,
        dest: String,
        options: SyncOptions,
        stallTimeoutMs: Long,
    ): Flow<SyncProgress> =
        // A one-way COPY/backup tolerates file-level errors (one unreadable source file
        // mustn't fail the whole run — rclone copies the rest and continues). A delete
        // MIRROR or MOVE must NOT: a mirror that proceeded despite unreadable source files
        // could delete their cloud counterparts; a move that proceeds despite errors would
        // delete the source after only a partial transfer, risking data loss.
        runJobWithProgress(
            tolerateFileErrors = !options.deleteExtraneous && !options.deleteSource,
            stallTimeoutMs = stallTimeoutMs,
        ) { d ->
            // Fail-fast on the one flag combination that has no coherent rclone command:
            // move (delete source) AND mirror (delete extraneous on dest) are mutually
            // exclusive. The editor normalizes this away, but a malformed persisted task
            // or a non-UI entry point must not silently fall through to sync/move.
            if (options.deleteSource && options.deleteExtraneous) {
                throw VirgaError.Rclone(
                    message = "deleteSource and deleteExtraneous are mutually exclusive.",
                )
            }
            val (srcFs, dstFs) = when (options.direction) {
                SyncDirection.DOWNLOAD -> dest to source
                else -> source to dest
            }
            val command = when {
                options.deleteSource -> "sync/move"
                options.deleteExtraneous -> "sync/sync"
                else -> "sync/copy"
            }
            rc(d, command, buildJsonObject {
                put("srcFs", srcFs)
                put("dstFs", dstFs)
                put("_async", true)
                putConfig(options)
                putFilters(options.filters, options.minSize, options.maxSize, options.minAge, options.maxAge)
            })
        }

    override fun bisync(
        path1: String,
        path2: String,
        options: BisyncOptions,
        stallTimeoutMs: Long,
    ): Flow<SyncProgress> =
        runJobWithProgress(stallTimeoutMs = stallTimeoutMs) { d ->
            // rclone bisync requires BOTH endpoints to already exist — even a
            // first run with --resync aborts with "directory not found" if the
            // remote (path2) dir is missing. The local path1 is a user-picked
            // folder that exists; ensure the remote path2 dir exists too before a
            // resync so a first bisync can bootstrap. mkdir is idempotent.
            if (options.resync) {
                val (fs, remote) = splitFs(path2)
                if (remote.isNotEmpty()) {
                    rc(d, "operations/mkdir", buildJsonObject {
                        put("fs", fs)
                        put(KEY_REMOTE, remote)
                    })
                }
            }
            rc(d, "sync/bisync", buildJsonObject {
                put("path1", path1)
                put("path2", path2)
                put("_async", true)
                if (options.resync) put("resync", true)
                putConfig(options)
                putFilters(options.filters, options.minSize, options.maxSize, options.minAge, options.maxAge)
            })
        }

    override suspend fun deleteFile(remote: String, path: String): Unit = withLease { d ->
        rc(d, "operations/deletefile", buildJsonObject {
            put("fs", remote)
            put(KEY_REMOTE, path)
        })
    }

    override suspend fun moveFile(source: String, dest: String): Unit = withLease { d ->
        val (srcFs, srcRemote) = splitFs(source)
        val (dstFs, dstRemote) = splitFs(dest)
        rc(d, "operations/movefile", buildJsonObject {
            put(KEY_SRC_FS, srcFs); put(KEY_SRC_REMOTE, srcRemote)
            put(KEY_DST_FS, dstFs); put(KEY_DST_REMOTE, dstRemote)
        })
    }

    override suspend fun mkdir(remote: String, path: String): Unit = withLease { d ->
        rc(d, "operations/mkdir", buildJsonObject {
            put("fs", remote)
            put(KEY_REMOTE, path)
        })
    }

    override suspend fun copyFile(source: String, dest: String): Unit = withLease { d ->
        val (srcFs, srcRemote) = splitFs(source)
        val (dstFs, dstRemote) = splitFs(dest)
        rc(d, "operations/copyfile", buildJsonObject {
            put(KEY_SRC_FS, srcFs); put(KEY_SRC_REMOTE, srcRemote)
            put(KEY_DST_FS, dstFs); put(KEY_DST_REMOTE, dstRemote)
        })
    }

    override suspend fun downloadFile(
        remoteName: String,
        remotePath: String,
        destDir: String,
        destName: String,
    ): Unit = withLease { d ->
        rc(d, "operations/copyfile", buildJsonObject {
            put(KEY_SRC_FS, rootFs(remoteName)); put(KEY_SRC_REMOTE, remotePath)
            put(KEY_DST_FS, destDir); put(KEY_DST_REMOTE, destName)
        })
    }

    override suspend fun uploadFile(
        srcDir: String,
        srcName: String,
        remoteName: String,
        remotePath: String,
    ): Unit = withLease { d ->
        rc(d, "operations/copyfile", buildJsonObject {
            put(KEY_SRC_FS, srcDir); put(KEY_SRC_REMOTE, srcName)
            put(KEY_DST_FS, rootFs(remoteName)); put(KEY_DST_REMOTE, remotePath)
        })
    }

    override suspend fun purge(remote: String, path: String): Unit = withLease { d ->
        rc(d, "operations/purge", buildJsonObject {
            put("fs", remote)
            put(KEY_REMOTE, path)
        })
    }

    override suspend fun testConnectivity(remoteName: String): Result<Unit> = withLease { d ->
        val fs = rootFs(remoteName)
        try {
            rc(d, "operations/about", buildJsonObject { put("fs", fs) })
            Result.success(Unit)
        } catch (e: CancellationException) {
            // Caller cancellation must propagate, not be reported as a connectivity failure.
            throw e
        } catch (_: Throwable) {
            try {
                rc(d, "operations/list", buildJsonObject { put("fs", fs); put(KEY_REMOTE, "") })
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }

    override fun check(
        source: String,
        dest: String,
        options: SyncOptions,
        stallTimeoutMs: Long,
    ): Flow<SyncProgress> =
        // check never deletes, so file-level errors (a missing file) are informational.
        runJobWithProgress(tolerateFileErrors = true, stallTimeoutMs = stallTimeoutMs) { d ->
            rc(d, "operations/check", buildJsonObject {
                put("srcFs", source)
                put("dstFs", dest)
                put("_async", true)
                putConfig(options)
                putFilters(options.filters, options.minSize, options.maxSize, options.minAge, options.maxAge)
            })
        }

    override suspend fun dedupe(remoteName: String, dedupeMode: String): Result<Unit> {
        var leased = false
        return try {
            // Validate the mode at this boundary before it reaches rclone. It's an
            // internal value today ("skip"), not JSON/shell-injectable, but a corrupt
            // DB row or a future caller shouldn't push an arbitrary string through.
            require(dedupeMode in VALID_DEDUPE_MODES) { "Unsupported dedupe mode: $dedupeMode" }
            val d = acquireDaemon()
            leased = true
            // rclone exposes `dedupe` only as a CLI command, not an operations/* RC
            // method, so run it through core/command. COMBINED_OUTPUT returns
            // {"error": <bool>, "result": "<text>"}; a true error flag means the
            // dedupe itself failed even though the HTTP call succeeded.
            val resp = rc(d, "core/command", buildJsonObject {
                put("command", "dedupe")
                putJsonArray("arg") { add(rootFs(remoteName)) }
                putJsonObject("opt") { put("dedupe-mode", dedupeMode) }
                put("returnType", "COMBINED_OUTPUT")
            })
            if (resp["error"]?.jsonPrimitive?.booleanOrNull == true) {
                Result.failure(
                    VirgaError.Rclone(
                        message = resp["result"]?.jsonPrimitive?.contentOrNull ?: "dedupe failed",
                    ),
                )
            } else {
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            if (leased) withContext(NonCancellable) { runCatching { releaseDaemon() } }
        }
    }

    /**
     * Fetches transfer records for [group] from `core/transferred` (scoped so a
     * concurrent run's transfers on the shared daemon aren't mis-attributed). Returns
     * both success and failure entries; callers filter to entries where
     * [TransferredFile.error] is non-blank. Entries with a missing or blank `name` are
     * skipped defensively. Uses a refcount lease (withLease) consistent with other
     * one-shot read operations.
     *
     * RC response shape (rclone ≥ 1.50, confirmed present in v1.74.2):
     *   {"transferred": [{"name": "...", "error": "...", "bytes": N, ...}, ...]}
     * The "transferred" array may be absent (empty stats) or contain objects missing
     * optional keys — all handled defensively below.
     */
    override suspend fun transferredFiles(group: String): List<TransferredFile> = withLease { d ->
        // Scope to the run's stats group ("job/<id>"): the shared daemon may be
        // running other concurrent "sync all" jobs whose transfers would otherwise
        // be returned and mis-attributed to this run.
        val result = rc(d, "core/transferred", buildJsonObject { put("group", group) })
        val entries: List<JsonElement> = result["transferred"]?.jsonArray ?: emptyList()
        entries.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull()
            val name = obj?.get("name")?.jsonPrimitive?.contentOrNull
            // Skip entries with no usable name (if/else avoids labelled returns).
            if (name.isNullOrBlank()) {
                null
            } else {
                TransferredFile(name = name, error = obj["error"]?.jsonPrimitive?.contentOrNull ?: "")
            }
        }
    }

    override suspend fun about(remoteName: String): RemoteQuota = withLease { d ->
        val result = rc(d, "operations/about", buildJsonObject {
            put("fs", rootFs(remoteName))
        })
        RemoteQuota(
            total = result["total"]?.jsonPrimitive?.longOrNull,
            used = result["used"]?.jsonPrimitive?.longOrNull,
            free = result["free"]?.jsonPrimitive?.longOrNull,
        )
    }

    /**
     * Resolves a finished rclone job into the terminal [SyncProgress] to emit, or throws.
     *
     * rclone reports `success:false` whenever ANY file errored, even though it copied
     * every other file and continued. For a copy/backup ([tolerateFileErrors] true) a
     * non-fatal file-level error (`core/stats.fatalError == false`) is a PARTIAL SUCCESS:
     * return the terminal stats (carrying `errors = N`) so the worker can summarise rather
     * than fail the whole run. A fatal abort, or a delete-mirror ([tolerateFileErrors]
     * false), still throws. `core/stats` is fetched only on the success or tolerate paths,
     * so a mirror failure pays no extra round-trip before throwing.
     */
    private suspend fun finishedJobResult(
        d: RcloneDaemon,
        status: JsonObject,
        group: String,
        tolerateFileErrors: Boolean,
    ): SyncProgress {
        val success = status["success"]?.jsonPrimitive?.booleanOrNull ?: false
        // Stamp the run's stats group on the terminal emission so the worker can
        // scope its per-file failure query (core/transferred) to THIS run.
        if (success) return statsFor(d, group).copy(statsGroup = group)
        // A copy/backup treats a non-fatal file-level failure as partial success.
        if (tolerateFileErrors) partialSuccessStats(d, group)?.let { return it.copy(statsGroup = group) }
        throw classifyJobError(status["error"]?.jsonPrimitive?.contentOrNull ?: "sync failed")
    }

    /** Final transfer stats for a finished job, as [SyncProgress]. */
    private suspend fun statsFor(d: RcloneDaemon, group: String): SyncProgress =
        rc(d, CMD_CORE_STATS, buildJsonObject { put(KEY_GROUP, group) }).toSyncProgress()

    /**
     * For a finished-unsuccessful job: the terminal [SyncProgress] if the failure was only
     * non-fatal file-level errors (partial success), or null to fall through to the job
     * error. If the stats probe itself fails we can't confirm non-fatal, so return null
     * (don't mask the real job error) — and never swallow cancellation.
     */
    private suspend fun partialSuccessStats(d: RcloneDaemon, group: String): SyncProgress? {
        val finalStats = try {
            rc(d, CMD_CORE_STATS, buildJsonObject { put(KEY_GROUP, group) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null
        }
        val fatal = finalStats["fatalError"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (fatal) null else finalStats.toSyncProgress()
    }

    /**
     * Starts an async RC job then polls job/status every tick. core/stats is
     * fetched on the same tick to build the progress snapshot; the final
     * completion stats are extracted from the job/status response to avoid a
     * redundant extra core/stats round-trip.
     */
    private fun runJobWithProgress(
        tolerateFileErrors: Boolean = false,
        stallTimeoutMs: Long = STALL_TIMEOUT_MS,
        start: suspend (RcloneDaemon) -> JsonObject,
    ): Flow<SyncProgress> = flow {
        val d = ensureDaemon()
        val startResult = start(d)
        val jobId = startResult["jobid"]?.jsonPrimitive?.intOrNull
            ?: throw VirgaError.Rclone(message = "rclone did not return a job id")
        val group = "job/$jobId"

        // Stall guard: abort if the job never finishes AND none of bytes / transferred
        // files / deletes / checks advance for STALL_TIMEOUT_MS — otherwise a wedged job
        // would leave the worker (and its sync_run row) stuck RUNNING indefinitely.
        // Tracking `checks` (not just transferred bytes) is what keeps rclone's long
        // listing/compare/deletion phases — which move no bytes — from being mistaken
        // for a stall. A truly hung (unresponsive) daemon is also caught by the RC
        // call's readTimeout. MAX_VALUE means "clock not yet armed"; the -1 sentinels
        // differ from rclone's zeroed first stats, so the clock arms on the first poll.
        var lastBytes = -1L
        var lastTransfers = -1
        var lastDeletes = -1
        var lastChecks = -1
        var lastProgressAtMs = Long.MAX_VALUE
        var lastTransferringName: String? = null

        // Track whether the rclone job reached a terminal state. If we exit the
        // loop for any other reason (collector cancellation, stall-guard abort,
        // an exception), the async job is still running on the shared daemon, so
        // we must explicitly stop it — daemon teardown only happens when the LAST
        // lease is released, which doesn't occur under concurrent "sync all".
        var jobFinished = false
        try {
            while (true) {
                val status = rc(d, "job/status", buildJsonObject { put("jobid", jobId) })
                val finished = status["finished"]?.jsonPrimitive?.booleanOrNull ?: false
                if (finished) {
                    jobFinished = true
                    emit(finishedJobResult(d, status, group, tolerateFileErrors))
                    break
                }
                // Fetch stats only while running (drives the throttled UI update).
                val stats = rc(d, CMD_CORE_STATS, buildJsonObject { put(KEY_GROUP, group) })
                val progress = stats.toSyncProgress()
                progress.transferringNames.firstOrNull()?.let { lastTransferringName = it }
                val checks = stats["checks"]?.jsonPrimitive?.intOrNull ?: 0
                if (progress.bytesTransferred != lastBytes ||
                    progress.transferredFiles != lastTransfers ||
                    progress.deletes != lastDeletes ||
                    checks != lastChecks
                ) {
                    lastBytes = progress.bytesTransferred
                    lastTransfers = progress.transferredFiles
                    lastDeletes = progress.deletes
                    lastChecks = checks
                    lastProgressAtMs = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastProgressAtMs > stallTimeoutMs) {
                    val madeProgress = lastProgressAtMs != Long.MAX_VALUE
                    if (tolerateFileErrors && madeProgress) {
                        // Copy/backup: keep what already transferred. Stop the job, read
                        // final stats, and report a partial success naming the wedged file.
                        jobFinished = true
                        runCatching { rc(d, "job/stop", buildJsonObject { put("jobid", jobId) }) }
                        val finalStats = statsFor(d, group)
                        emit(
                            finalStats.copy(
                                errors = maxOf(finalStats.errors, 1),
                                stalledFile = lastTransferringName,
                                statsGroup = group,
                            ),
                        )
                        break
                    }
                    throw VirgaError.Stall(
                        file = lastTransferringName,
                        message = stallMessage(stallTimeoutMs, lastTransferringName),
                    )
                }
                emit(progress)
                delay(POLL_INTERVAL_MS)
            }
        } finally {
            // Abort the rclone job if it didn't finish on its own. NonCancellable so
            // the stop survives the very cancellation that triggered this finally.
            if (!jobFinished) {
                withContext(NonCancellable) {
                    runCatching { rc(d, "job/stop", buildJsonObject { put("jobid", jobId) }) }
                    // If no other consumer holds the daemon, a job that won't confirm
                    // stopped is a wedged (kernel-blocked) transfer thread that job/stop
                    // can't reach. Force-kill the daemon process so we don't leak it.
                    if (leaseCount() <= 1 && !jobStopped(d, jobId)) {
                        runCatching { daemonManager.stop(d) }
                    }
                }
            }
        }
    }.flowOn(dispatchers.io)

    private suspend fun ensureDaemon(): RcloneDaemon = lock.withLock { ensureDaemonLocked() }

    /**
     * Runs a one-shot RC op under a refcount lease (rclone-M2/M3): [acquireDaemon] starts-or-reuses
     * the daemon and takes a lease, [block] issues the RC call OUTSIDE the lock (acquire/release take
     * the [lock] themselves; the Mutex is non-reentrant so [block] must NOT re-acquire it), and the
     * finally [releaseDaemon] drops the lease — tearing the daemon down + re-encrypting the plaintext
     * config when this was the only consumer (so an isolated op starts→uses→stops the daemon instead
     * of leaking the rclone process + decrypted OAuth tokens), while a concurrent sync's teardown can't
     * stop the daemon mid-call. If a leased consumer (a SyncWorker doing conflict-detection listDir) is
     * already holding a lease, the refcount just goes 2→1 here and the daemon survives.
     */
    private suspend fun <T> withLease(block: suspend (RcloneDaemon) -> T): T {
        val d = acquireDaemon()
        try {
            return block(d)
        } finally {
            releaseDaemon()
        }
    }

    /**
     * Runs [block] holding [lock] with an exclusively-owned daemon, then atomically tears
     * the daemon down and either re-encrypts the daemon-written plaintext on success or
     * discards it on failure (finally), so a failed mutation can never leave decrypted
     * OAuth tokens on disk. Refuses while a sync holds a lease — the end-of-mutation
     * teardown would kill it. [block] receives the daemon directly and must NOT re-acquire
     * [lock] (the Mutex is non-reentrant).
     *
     * The finally teardown runs inside [NonCancellable]: a cancelled caller (user backs
     * out mid-createRemote, VM scope dies mid-daemon-OAuth) must still stop the rclone
     * process and persist-or-discard the plaintext config rather than leak both.
     */
    private suspend fun <T> withExclusiveDaemon(block: suspend (RcloneDaemon) -> T): T {
        return lock.withLock {
            if (leases > 0) {
                throw VirgaError.Rclone(message = "Stop running syncs before modifying remotes.")
            }
            val d = ensureDaemonLocked()
            var ok = false
            try {
                val result = block(d)
                ok = true
                result
            } finally {
                withContext(NonCancellable) {
                    daemonManager.stop(d)
                    daemon = null
                    if (ok) configManager.persistAndCleanup() else runCatching { configManager.cleanup() }
                }
            }
        }
    }

    private suspend fun mutatingConfig(block: suspend (RcloneDaemon) -> Unit) = withExclusiveDaemon(block)

    /**
     * Runs an interactive daemon-mediated OAuth [block] under a refcount LEASE rather
     * than the exclusive [lock]. The block typically suspends for minutes — the user
     * runs `rclone authorize` elsewhere and pastes the result back — so holding the
     * engine Mutex for its duration (as [withExclusiveDaemon] would) stalls every other
     * engine op (quota, listing, a scheduled SyncWorker's [acquireDaemon]) until the
     * paste or the 600s timeout. Instead:
     *  - briefly hold [lock] to refuse while a sync leases the daemon (a config mutation
     *    would race the OAuth config write — same guard as [withExclusiveDaemon]), ensure
     *    the daemon, and take a lease;
     *  - run [block] OUTSIDE the lock, so concurrent READS share the daemon harmlessly
     *    and concurrent MUTATIONS stay refused by the leases>0 guard;
     *  - tear down in finally with the SAME semantics as [withExclusiveDaemon] — persist
     *    on success / discard on failure — but only when this is the LAST consumer; if a
     *    sync co-leases, its own release persists later (the daemon-written remote stays
     *    in the live config meanwhile, so the post-flow connectivity test still sees it).
     *
     * The teardown is [NonCancellable]: a cancelled caller must still drop the lease and
     * stop the rclone process + handle the plaintext config rather than leak them.
     */
    override suspend fun <T> withDaemonForOAuth(block: suspend (RcloneDaemon) -> T): T {
        val d = lock.withLock {
            if (leases > 0) {
                throw VirgaError.Rclone(message = "Stop running syncs before modifying remotes.")
            }
            val daemon = ensureDaemonLocked()
            leases++
            daemon
        }
        var ok = false
        try {
            val result = block(d)
            ok = true
            return result
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (leases > 0) leases--
                    if (leases == 0) {
                        daemonManager.stop(d)
                        daemon = null
                        if (ok) configManager.persistAndCleanup() else runCatching { configManager.cleanup() }
                    } else if (!ok) {
                        // A concurrent sync still leases the daemon, so we can't tear it
                        // down to discard now. Mark the working config tainted so the last
                        // leaseholder's teardown discards this failed OAuth's token-less
                        // remote instead of persisting it (matching withExclusiveDaemon's
                        // discard-on-failure even across the co-lease).
                        discardWorkingConfig = true
                    }
                    // else (ok, leases>0): a concurrent sync still leases the daemon —
                    // leave it running; that sync's releaseDaemon persists the
                    // OAuth-written remote later.
                }
            }
        }
    }

    private suspend fun rc(d: RcloneDaemon, command: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        apiClient.call(d.baseUrl, d.user, d.pass, command, params)

    /** Current daemon lease count, read under the lock. */
    private suspend fun leaseCount(): Int = lock.withLock { leases }

    /** Polls job/status briefly; true once the job reports finished, false if it stays
     *  unfinished through the grace window (a wedged thread job/stop can't reach). */
    private suspend fun jobStopped(d: RcloneDaemon, jobId: Int): Boolean {
        repeat(JOB_STOP_CONFIRM_POLLS) {
            val finished = runCatching {
                rc(d, "job/status", buildJsonObject { put("jobid", jobId) })["finished"]
                    ?.jsonPrimitive?.booleanOrNull
            }.getOrNull() ?: false
            if (finished) return true
            delay(JOB_STOP_CONFIRM_INTERVAL_MS)
        }
        return false
    }

    private fun stallMessage(timeoutMs: Long, file: String?): String {
        val base = "Sync stalled — no progress for ${timeoutMs / 1000}s."
        return if (file != null) "$base Last read: $file" else base
    }

    private companion object {
        const val TAG = "RcloneEngine"
        // rclone's accepted --dedupe-mode values (rclone v1.74). Guards dedupe()'s
        // boundary so only a known mode reaches the daemon.
        val VALID_DEDUPE_MODES = setOf(
            "skip", "first", "newest", "oldest", "rename", "largest", "smallest",
        )
        // rclone RC parameter key naming the (sub)path within a remote's filesystem.
        const val KEY_REMOTE = "remote"
        // rclone RC command + param for fetching a job's transfer statistics.
        const val CMD_CORE_STATS = "core/stats"
        const val KEY_GROUP = "group"
        const val POLL_INTERVAL_MS = 750L
        // Max time an in-flight job may make zero progress before we abort it.
        const val STALL_TIMEOUT_MS = RcloneEngine.DEFAULT_STALL_TIMEOUT_MS
        const val JOB_STOP_CONFIRM_POLLS = 4
        const val JOB_STOP_CONFIRM_INTERVAL_MS = 250L
        // operations/copyfile and operations/movefile parameter keys.
        const val KEY_SRC_FS = "srcFs"
        const val KEY_SRC_REMOTE = "srcRemote"
        const val KEY_DST_FS = "dstFs"
        const val KEY_DST_REMOTE = "dstRemote"

        /** rclone fs spec for a remote's root: the remote name followed by ':'. */
        fun rootFs(remoteName: String): String = "$remoteName:"
    }
}
