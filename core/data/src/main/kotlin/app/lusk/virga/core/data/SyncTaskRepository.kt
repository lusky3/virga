package app.lusk.virga.core.data

import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.database.dao.SyncTaskDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncTaskRepository @Inject constructor(
    private val taskDao: SyncTaskDao,
) {
    val tasks: Flow<List<SyncTask>> = taskDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun task(id: Long): Flow<SyncTask?> = taskDao.observeById(id).map { it?.toDomain() }

    suspend fun getTask(id: Long): SyncTask? = taskDao.getById(id)?.toDomain()

    suspend fun scheduledTasks(): List<SyncTask> = taskDao.getScheduled().map { it.toDomain() }

    /** Inserts a new task or updates an existing one. Returns the row id. */
    suspend fun save(task: SyncTask): Long {
        val entity = task.toEntity()
        return if (entity.id == 0L) {
            taskDao.insert(entity)
        } else {
            taskDao.update(entity)
            entity.id
        }
    }

    suspend fun delete(task: SyncTask) = taskDao.delete(task.toEntity())
}
