package app.lusk.virga.core.data

import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.database.dao.RemoteDao
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.rclone.RcloneEngine
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import kotlinx.coroutines.flow.Flow
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
    val remotes: Flow<List<RemoteEntity>> = remoteDao.observeAll()

    /** Pulls the live remote list from rclone and replaces the cached rows atomically. */
    suspend fun refresh(): Result<Unit> = runCatching {
        val live = engine.listRemotes()
        remoteDao.replaceAll(
            live.map { RemoteEntity(name = it.name, type = it.type, displayName = it.name) },
        )
    }

    suspend fun addRemote(name: String, type: String, params: Map<String, String>): Result<Unit> {
        val created = engine.createRemote(name, type, params)
        if (created.isFailure) return created
        return refresh()
    }

    suspend fun deleteRemote(name: String): Result<Unit> {
        val deleted = engine.deleteRemote(name)
        if (deleted.isFailure) return deleted
        remoteDao.deleteByName(name)
        return Result.success(Unit)
    }

    suspend fun importConfig(confContent: String): Result<Unit> {
        if (confContent.isBlank()) {
            return Result.failure(VirgaError.Rclone(message = "Empty config"))
        }
        val imported = engine.importConfig(confContent)
        if (imported.isFailure) return imported
        return refresh()
    }

    suspend fun exportConfig(): String = configManager.exportPlaintext()
}
