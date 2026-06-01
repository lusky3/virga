package app.lusk.virga.core.data

import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteProvider
import app.lusk.virga.core.common.model.RemoteQuota
import app.lusk.virga.core.database.dao.RemoteDao
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.rclone.RcloneEngine
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source of truth for configured remotes. The Room cache drives list UIs; the
 * rclone daemon is the authority and is reconciled into the cache on [refresh].
 */
@Singleton
class RemoteRepository @Inject constructor(
    private val remoteDao: RemoteDao,
    private val engine: RcloneEngine,
    private val configManager: RcloneConfigManager,
) {
    /** Configured remotes as domain models (the Room entity stays in this layer). */
    val remotes: Flow<List<Remote>> = remoteDao.observeAll().map { rows ->
        rows.map { Remote(name = it.name, type = it.type) }
    }

    /** Pulls the live remote list from rclone and replaces the cached rows atomically. */
    suspend fun refresh(): Result<Unit> = runCatching {
        val live = engine.listRemotes()
        remoteDao.replaceAll(
            live.map { RemoteEntity(name = it.name, type = it.type, displayName = it.name) },
        )
    }

    suspend fun addRemote(name: String, type: String, params: Map<String, String>): Result<Unit> =
        runCatching { engine.createRemote(name, type, params) }
            .mapCatching { refresh().getOrThrow() }

    /**
     * Creates a `crypt:` remote wrapping [baseRemoteSpec]. Delegates password
     * obscuring to rclone (via [RcloneEngine.createCryptRemote]) — no plaintext
     * password material is stored or logged by this layer.
     */
    suspend fun addCryptRemote(
        name: String,
        baseRemoteSpec: String,
        password: String,
        salt: String?,
    ): Result<Unit> =
        runCatching { engine.createCryptRemote(name, baseRemoteSpec, password, salt) }
            .mapCatching { refresh().getOrThrow() }

    suspend fun deleteRemote(name: String): Result<Unit> =
        runCatching {
            engine.deleteRemote(name)
            remoteDao.deleteByName(name)
        }

    suspend fun importConfig(confContent: String): Result<Unit> {
        if (confContent.isBlank()) {
            return Result.failure(VirgaError.Rclone(message = "Empty config"))
        }
        return runCatching { engine.importConfig(confContent) }
            .mapCatching { refresh().getOrThrow() }
    }

    suspend fun exportConfig(): String = configManager.exportPlaintext()

    /** Fetches storage quota for [remoteName]. Returns [Result.failure] when offline or unsupported. */
    suspend fun about(remoteName: String): Result<RemoteQuota> =
        runCatching { engine.about(remoteName) }

    /**
     * Returns the rclone provider schema (backend types + per-backend option list).
     * Never throws: returns an empty list when the daemon is unavailable or the call
     * fails — callers should fall back to freeform input in that case.
     */
    suspend fun providers(): List<RemoteProvider> = engine.providers()
}
