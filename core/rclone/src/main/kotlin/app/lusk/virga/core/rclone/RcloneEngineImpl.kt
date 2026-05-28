package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.TransferProgress
import app.lusk.virga.core.rclone.api.RcApiClient
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import app.lusk.virga.core.rclone.daemon.RcloneDaemonManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
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

    override suspend fun startDaemon(): RcloneDaemon = lock.withLock {
        daemon?.takeIf { daemonManager.isAlive(it) }?.let { return it }
        val configFile = configManager.decryptForDaemon()
        daemonManager.start(configFile).also { daemon = it }
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
    ): Result<Unit> = runCatchingRclone {
        val d = ensureDaemon()
        rc(d, "config/create", buildJsonObject {
            put("name", name)
            put("type", type)
            putJsonObject("parameters") { params.forEach { (k, v) -> put(k, v) } }
        })
        configManager.persistAndCleanup()
        // Daemon keeps running on the now-deleted plaintext temp; restart lazily.
        daemon = null
    }

    override suspend fun deleteRemote(name: String): Result<Unit> = runCatchingRclone {
        val d = ensureDaemon()
        rc(d, "config/delete", buildJsonObject { put("name", name) })
        configManager.persistAndCleanup()
        daemon = null
    }

    override suspend fun importConfig(confContent: String): Result<Unit> = runCatchingRclone {
        stopDaemon()
        configManager.import(confContent)
    }

    override suspend fun listDir(remote: String, path: String): List<FileItem> {
        val d = ensureDaemon()
        val result = rc(d, "operations/list", buildJsonObject {
            put("fs", remote)
            put("remote", path)
        })
        val list = result["list"]?.jsonArray ?: return emptyList()
        return list.map { it.jsonObject }.map { obj ->
            FileItem(
                name = obj["Name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                path = obj["Path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                isDir = obj["IsDir"]?.jsonPrimitive?.booleanOrNull ?: false,
                size = obj["Size"]?.jsonPrimitive?.longOrNull ?: 0L,
                modTimeEpochMs = null,
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
                putConfig(options.bwLimit, options.transfers, options.checkers, options.bufferSize, options.dryRun)
                putFilters(options.filters)
            })
        }

    override fun bisync(path1: String, path2: String, options: BisyncOptions): Flow<SyncProgress> =
        runJobWithProgress { d ->
            rc(d, "sync/bisync", buildJsonObject {
                put("path1", path1)
                put("path2", path2)
                put("_async", true)
                if (options.resync) put("resync", true)
                putConfig(options.bwLimit, options.transfers, options.checkers, "16M", options.dryRun)
                putFilters(options.filters)
            })
        }

    override fun copyFile(source: String, dest: String): Flow<TransferProgress> = flow {
        val d = ensureDaemon()
        rc(d, "operations/copyfile", buildJsonObject {
            val (srcFs, srcRemote) = splitFs(source)
            val (dstFs, dstRemote) = splitFs(dest)
            put("srcFs", srcFs); put("srcRemote", srcRemote)
            put("dstFs", dstFs); put("dstRemote", dstRemote)
        })
        emit(TransferProgress(name = dest, bytes = 0, size = 0, speedBytesPerSec = 0.0))
    }.flowOn(dispatchers.io)

    /** Starts an async RC job, then polls core/stats + job/status until done. */
    private fun runJobWithProgress(start: suspend (RcloneDaemon) -> JsonObject): Flow<SyncProgress> = flow {
        val d = ensureDaemon()
        val startResult = start(d)
        val jobId = startResult["jobid"]?.jsonPrimitive?.intOrNull
            ?: throw VirgaError.Rclone(message = "rclone did not return a job id")
        val group = "job/$jobId"

        while (true) {
            val stats = rc(d, "core/stats", buildJsonObject { put("group", group) })
            emit(stats.toSyncProgress())
            val status = rc(d, "job/status", buildJsonObject { put("jobid", jobId) })
            val finished = status["finished"]?.jsonPrimitive?.booleanOrNull ?: false
            if (finished) {
                val success = status["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!success) {
                    val err = status["error"]?.jsonPrimitive?.contentOrNull ?: "sync failed"
                    throw VirgaError.Rclone(message = err)
                }
                emit(rc(d, "core/stats", buildJsonObject { put("group", group) }).toSyncProgress())
                break
            }
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

    private suspend fun ensureDaemon(): RcloneDaemon =
        daemon?.takeIf { daemonManager.isAlive(it) } ?: startDaemon()

    private suspend fun rc(d: RcloneDaemon, command: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        apiClient.call(d.baseUrl, d.user, d.pass, command, params)

    private inline fun runCatchingRclone(block: () -> Unit): Result<Unit> =
        try {
            block(); Result.success(Unit)
        } catch (e: VirgaError) {
            Result.failure(e)
        }

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
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putConfig(
    bwLimit: String?,
    transfers: Int,
    checkers: Int,
    bufferSize: String,
    dryRun: Boolean,
) {
    putJsonObject("_config") {
        put("Transfers", transfers)
        put("Checkers", checkers)
        put("BufferSize", bufferSize)
        if (dryRun) put("DryRun", true)
        if (!bwLimit.isNullOrBlank()) put("BwLimit", bwLimit)
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
