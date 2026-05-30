package app.lusk.virga.core.data

import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.database.dao.SyncRunDao
import app.lusk.virga.core.database.entity.SyncRunEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncHistoryRepository @Inject constructor(
    private val runDao: SyncRunDao,
) {
    val recentRuns: Flow<List<SyncRunEntity>> = runDao.observeRecent()

    fun observeRun(id: Long): Flow<SyncRunEntity?> = runDao.observeById(id)

    fun runsForTask(taskId: Long): Flow<List<SyncRunEntity>> = runDao.observeForTask(taskId)

    /** Records the start of a run and returns its id for later [finishRun]. */
    suspend fun startRun(taskId: Long): Long = runDao.insert(
        SyncRunEntity(
            taskId = taskId,
            startedAtEpochMs = System.currentTimeMillis(),
            status = SyncStatus.RUNNING,
        ),
    )

    suspend fun finishRun(
        runId: Long,
        taskId: Long,
        startedAtEpochMs: Long,
        status: SyncStatus,
        filesTransferred: Int,
        bytesTransferred: Long,
        errorCount: Int,
        errorMessage: String? = null,
        logPath: String? = null,
    ) = runDao.update(
        SyncRunEntity(
            id = runId,
            taskId = taskId,
            startedAtEpochMs = startedAtEpochMs,
            endedAtEpochMs = System.currentTimeMillis(),
            status = status,
            filesTransferred = filesTransferred,
            bytesTransferred = bytesTransferred,
            errorCount = errorCount,
            errorMessage = errorMessage,
            logPath = logPath,
        ),
    )

    suspend fun pruneOlderThan(beforeEpochMs: Long) = runDao.pruneOlderThan(beforeEpochMs)

    suspend fun clearAll() = runDao.deleteAll()
}
