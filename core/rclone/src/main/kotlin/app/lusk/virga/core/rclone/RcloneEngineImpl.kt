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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
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
                configManager.persistAndCleanup()
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
                configManager.persistAndCleanup()
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
            configManager.persistAndCleanup()
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
                    put("remote", baseRemoteSpec)
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
            put("remote", path)
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

    override fun sync(source: String, dest: String, options: SyncOptions): Flow<SyncProgress> =
        runJobWithProgress { d ->
            val (srcFs, dstFs) = when (options.direction) {
                SyncDirection.DOWNLOAD -> dest to source
                else -> source to dest
            }
            val command = if (options.deleteExtraneous) "sync/sync" else "sync/copy"
            rc(d, command, buildJsonObject {
                put("srcFs", srcFs)
                put("dstFs", dstFs)
                put("_async", true)
                putConfig(
                    bwLimit = options.bwLimit,
                    transfers = options.transfers,
                    checkers = options.checkers,
                    bufferSize = options.bufferSize,
                    dryRun = options.dryRun,
                    checksum = options.checksum,
                    backupDir = options.backupDir,
                    maxDelete = options.maxDelete,
                    extraConfig = options.extraConfig,
                )
                putFilters(options.filters)
            })
        }

    override fun bisync(path1: String, path2: String, options: BisyncOptions): Flow<SyncProgress> =
        runJobWithProgress { d ->
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
                        put("remote", remote)
                    })
                }
            }
            rc(d, "sync/bisync", buildJsonObject {
                put("path1", path1)
                put("path2", path2)
                put("_async", true)
                if (options.resync) put("resync", true)
                putConfig(
                    bwLimit = options.bwLimit,
                    transfers = options.transfers,
                    checkers = options.checkers,
                    bufferSize = options.bufferSize,
                    dryRun = options.dryRun,
                    checksum = options.checksum,
                    backupDir = options.backupDir,
                    maxDelete = options.maxDelete,
                    extraConfig = options.extraConfig,
                )
                putFilters(options.filters)
            })
        }

    override suspend fun deleteFile(remote: String, path: String): Unit = withLease { d ->
        rc(d, "operations/deletefile", buildJsonObject {
            put("fs", remote)
            put("remote", path)
        })
    }

    override suspend fun moveFile(source: String, dest: String): Unit = withLease { d ->
        val (srcFs, srcRemote) = splitFs(source)
        val (dstFs, dstRemote) = splitFs(dest)
        rc(d, "operations/movefile", buildJsonObject {
            put("srcFs", srcFs); put("srcRemote", srcRemote)
            put("dstFs", dstFs); put("dstRemote", dstRemote)
        })
    }

    override suspend fun testConnectivity(remoteName: String): Result<Unit> = withLease { d ->
        val fs = "$remoteName:"
        try {
            rc(d, "operations/about", buildJsonObject { put("fs", fs) })
            Result.success(Unit)
        } catch (e: CancellationException) {
            // Caller cancellation must propagate, not be reported as a connectivity failure.
            throw e
        } catch (_: Throwable) {
            try {
                rc(d, "operations/list", buildJsonObject { put("fs", fs); put("remote", "") })
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }

    override suspend fun about(remoteName: String): RemoteQuota = withLease { d ->
        val result = rc(d, "operations/about", buildJsonObject {
            put("fs", "$remoteName:")
        })
        RemoteQuota(
            total = result["total"]?.jsonPrimitive?.longOrNull,
            used = result["used"]?.jsonPrimitive?.longOrNull,
            free = result["free"]?.jsonPrimitive?.longOrNull,
        )
    }

    /**
     * Starts an async RC job then polls job/status every tick. core/stats is
     * fetched on the same tick to build the progress snapshot; the final
     * completion stats are extracted from the job/status response to avoid a
     * redundant extra core/stats round-trip.
     */
    private fun runJobWithProgress(start: suspend (RcloneDaemon) -> JsonObject): Flow<SyncProgress> = flow {
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
                    val success = status["success"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (!success) {
                        val err = status["error"]?.jsonPrimitive?.contentOrNull ?: "sync failed"
                        throw classifyJobError(err)
                    }
                    // Fetch final stats once on completion for accurate counters.
                    emit(rc(d, "core/stats", buildJsonObject { put("group", group) }).toSyncProgress())
                    break
                }
                // Fetch stats only while running (drives the throttled UI update).
                val stats = rc(d, "core/stats", buildJsonObject { put("group", group) })
                val progress = stats.toSyncProgress()
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
                } else if (System.currentTimeMillis() - lastProgressAtMs > STALL_TIMEOUT_MS) {
                    throw VirgaError.Rclone(
                        message = "Sync stalled — no progress for ${STALL_TIMEOUT_MS / 1000}s.",
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

    override suspend fun <T> withDaemonForOAuth(block: suspend (RcloneDaemon) -> T): T = withExclusiveDaemon(block)

    private suspend fun rc(d: RcloneDaemon, command: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        apiClient.call(d.baseUrl, d.user, d.pass, command, params)

    private companion object {
        const val TAG = "RcloneEngine"
        const val POLL_INTERVAL_MS = 750L
        // Max time an in-flight job may make zero progress before we abort it.
        const val STALL_TIMEOUT_MS = 120_000L
    }
}
