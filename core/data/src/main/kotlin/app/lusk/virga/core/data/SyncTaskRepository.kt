package app.lusk.virga.core.data

import app.lusk.virga.core.database.dao.SyncTaskDao
import app.lusk.virga.core.database.entity.SyncTaskEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncTaskRepository @Inject constructor(
    private val taskDao: SyncTaskDao,
) {
    val tasks: Flow<List<SyncTaskEntity>> = taskDao.observeAll()

    fun task(id: Long): Flow<SyncTaskEntity?> = taskDao.observeById(id)

    suspend fun getTask(id: Long): SyncTaskEntity? = taskDao.getById(id)

    suspend fun scheduledTasks(): List<SyncTaskEntity> = taskDao.getScheduled()

    /** Inserts a new task or updates an existing one. Returns the row id. */
    suspend fun save(task: SyncTaskEntity): Long =
        if (task.id == 0L) {
            taskDao.insert(task)
        } else {
            taskDao.update(task)
            task.id
        }

    suspend fun delete(task: SyncTaskEntity) = taskDao.delete(task)
}
