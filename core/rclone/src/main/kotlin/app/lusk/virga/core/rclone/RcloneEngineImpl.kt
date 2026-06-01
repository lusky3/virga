package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteQuota
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.rclone.api.RcApiClient
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import app.lusk.virga.core.rclone.daemon.RcloneDaemonManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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

    override suspend fun startDaemon(): RcloneDaemon = lock.withLock { ensureDaemonLocked() }

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

    override suspend fun stopDaemon() = lock.withLock {
        daemon?.let { daemonManager.stop(it) }
        daemon = null
        configManager.persistAndCleanup()
    }

    override suspend fun isDaemonHealthy(): Boolean {
        val d = daemon ?: return false
        if (!daemonManager.isAlive(d)) return false
        return runCatching { rc(d, "rc/noop") }.isSuccess
    }

    override suspend fun listRemotes(): List<Remote> {
        val config = getConfig()
        return config.remotes.map { (name, type) -> Remote(name, type) }
    }

    override suspend fun getConfig(): RcloneConfig {
        val d = ensureDaemon()
        val dump = rc(d, "config/dump")
        val remotes = dump.entries.associate { (name, value) ->
            name to (value.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "unknown")
        }
        return RcloneConfig(remotes)
    }

    override suspend fun createRemote(
        name: String,
        type: String,
        params: Map<String, String>,
    ) {
        mutatingConfig {
            val d = ensureDaemon()
            rc(d, "config/create", buildJsonObject {
                put("name", name)
                put("type", type)
                putJsonObject("parameters") { params.forEach { (k, v) -> put(k, v) } }
                // Create the remote non-interactively using the OAuth token already
                // supplied in [parameters]. Without nonInteractive, rclone's backend
                // config runs its own browser OAuth ("Waiting for code...") and the
                // config/create RC call blocks forever.
                putJsonObject("opt") { put("nonInteractive", true) }
            })
        }
    }

    override suspend fun deleteRemote(name: String) {
        mutatingConfig {
            val d = ensureDaemon()
            rc(d, "config/delete", buildJsonObject { put("name", name) })
        }
    }

    override suspend fun importConfig(confContent: String) {
        stopDaemon()
        configManager.import(confContent)
    }

    override suspend fun listDir(
        remote: String,
        path: String,
        recurse: Boolean,
        filters: List<String>,
    ): List<FileItem> {
        val d = ensureDaemon()
        val result = rc(d, "operations/list", buildJsonObject {
            put("fs", remote)
            put("remote", path)
            if (recurse) putJsonObject("opt") { put("recurse", true) }
            putFilters(filters)
        })
        val list = result["list"]?.jsonArray ?: return emptyList()
        return list.map { it.jsonObject }.map { obj ->
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
                    bufferSize = "16M",
                    dryRun = options.dryRun,
                    checksum = options.checksum,
                    backupDir = options.backupDir,
                    maxDelete = options.maxDelete,
                    extraConfig = options.extraConfig,
                )
                putFilters(options.filters)
            })
        }

    override suspend fun deleteFile(remote: String, path: String) {
        val d = ensureDaemon()
        rc(d, "operations/deletefile", buildJsonObject {
            put("fs", remote)
            put("remote", path)
        })
    }

    override suspend fun moveFile(source: String, dest: String) {
        val d = ensureDaemon()
        val (srcFs, srcRemote) = splitFs(source)
        val (dstFs, dstRemote) = splitFs(dest)
        rc(d, "operations/movefile", buildJsonObject {
            put("srcFs", srcFs); put("srcRemote", srcRemote)
            put("dstFs", dstFs); put("dstRemote", dstRemote)
        })
    }

    override suspend fun about(remoteName: String): RemoteQuota {
        val d = ensureDaemon()
        val result = rc(d, "operations/about", buildJsonObject {
            put("fs", "$remoteName:")
        })
        return RemoteQuota(
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

        // Stall guard: if the async job never reports finished AND makes no
        // progress (bytes + transferred files unchanged) for STALL_TIMEOUT_MS,
        // abort instead of polling forever — otherwise a wedged job would leave
        // the worker (and its sync_run row) stuck RUNNING indefinitely.
        var lastBytes = -1L
        var lastTransfers = -1
        var lastProgressAtMs = System.currentTimeMillis()

        while (true) {
            val status = rc(d, "job/status", buildJsonObject { put("jobid", jobId) })
            val finished = status["finished"]?.jsonPrimitive?.booleanOrNull ?: false
            if (finished) {
                val success = status["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!success) {
                    val err = status["error"]?.jsonPrimitive?.contentOrNull ?: "sync failed"
                    throw VirgaError.Rclone(message = err)
                }
                // Fetch final stats once on completion for accurate counters.
                emit(rc(d, "core/stats", buildJsonObject { put("group", group) }).toSyncProgress())
                break
            }
            // Fetch stats only while running (drives the throttled UI update).
            val stats = rc(d, "core/stats", buildJsonObject { put("group", group) })
            val progress = stats.toSyncProgress()
            if (progress.bytesTransferred != lastBytes || progress.transferredFiles != lastTransfers) {
                lastBytes = progress.bytesTransferred
                lastTransfers = progress.transferredFiles
                lastProgressAtMs = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastProgressAtMs > STALL_TIMEOUT_MS) {
                throw VirgaError.Rclone(
                    message = "Sync stalled — no progress for ${STALL_TIMEOUT_MS / 1000}s.",
                )
            }
            emit(progress)
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(dispatchers.io)

    private fun JsonObject.toSyncProgress(): SyncProgress = SyncProgress(
        bytesTransferred = this["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
        totalBytes = this["totalBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
        speedBytesPerSec = this["speed"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        transferredFiles = this["transfers"]?.jsonPrimitive?.intOrNull ?: 0,
        totalFiles = this["totalTransfers"]?.jsonPrimitive?.intOrNull ?: 0,
        etaSeconds = this["eta"]?.jsonPrimitive?.longOrNull,
        errors = this["errors"]?.jsonPrimitive?.intOrNull ?: 0,
    )

    private suspend fun ensureDaemon(): RcloneDaemon = lock.withLock { ensureDaemonLocked() }

    /**
     * Run a config-mutating block, then atomically tear down the daemon (so it
     * releases the stale plaintext config it has open) and either re-encrypt the
     * new config on success or discard the decrypted plaintext on failure. The
     * teardown + persist/cleanup run in a finally so a failed config/create can
     * never leave decrypted OAuth tokens on disk, and the daemon never keeps
     * serving a config that no longer exists.
     */
    private suspend fun mutatingConfig(block: suspend () -> Unit) {
        var ok = false
        try {
            block()
            ok = true
        } finally {
            lock.withLock {
                daemon?.let { daemonManager.stop(it) }
                daemon = null
            }
            if (ok) configManager.persistAndCleanup() else runCatching { configManager.cleanup() }
        }
    }

    private suspend fun rc(d: RcloneDaemon, command: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        apiClient.call(d.baseUrl, d.user, d.pass, command, params)

    /** Splits "remote:path/to/file" into ("remote:", "path/to/file"). */
    private fun splitFs(spec: String): Pair<String, String> {
        val idx = spec.indexOf(':')
        if (idx < 0) return spec to ""
        val fs = spec.substring(0, idx + 1)
        val remote = spec.substring(idx + 1)
        return fs to remote
    }

    private companion object {
        const val POLL_INTERVAL_MS = 750L
        // Max time an in-flight job may make zero progress before we abort it.
        const val STALL_TIMEOUT_MS = 120_000L
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putConfig(
    bwLimit: String?,
    transfers: Int,
    checkers: Int,
    bufferSize: String,
    dryRun: Boolean,
    checksum: Boolean = false,
    backupDir: String? = null,
    maxDelete: Int? = null,
    extraConfig: Map<String, Any> = emptyMap(),
) {
    putJsonObject("_config") {
        put("Transfers", transfers)
        put("Checkers", checkers)
        put("BufferSize", bufferSize)
        if (dryRun) put("DryRun", true)
        if (!bwLimit.isNullOrBlank()) put("BwLimit", bwLimit)
        // WS3.1 Tier-2 options
        if (checksum) put("CheckSum", true)
        if (!backupDir.isNullOrBlank()) put("BackupDir", backupDir)
        if (maxDelete != null) put("MaxDelete", maxDelete)
        // Merge power-user extra config entries. The Map<String, Any> contract
        // guarantees values are Boolean, Number, or String (enforced by
        // ExtraConfigParser before this point). Applied LAST, so an explicit
        // extraConfig entry (e.g. "CheckSum=false") intentionally overrides the
        // matching typed toggle above — the raw box is the power-user escape hatch.
        extraConfig.forEach { (key, value) ->
            when (value) {
                is Boolean -> put(key, JsonPrimitive(value))
                is Number -> put(key, JsonPrimitive(value))
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putFilters(filters: List<String>) {
    if (filters.isEmpty()) return
    putJsonObject("_filter") {
        putJsonArray("FilterRule") {
            filters.forEach { add(it) }
        }
    }
}
