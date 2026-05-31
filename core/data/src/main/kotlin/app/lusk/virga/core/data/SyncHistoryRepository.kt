package app.lusk.virga.core.data

import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.database.dao.SyncRunDao
import app.lusk.virga.core.database.entity.SyncRunEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncHistoryRepository @Inject constructor(
    private val runDao: SyncRunDao,
) {
    val recentRuns: Flow<List<SyncRun>> = runDao.observeRecent().map { rows -> rows.map { it.toDomain() } }

    fun observeRun(id: Long): Flow<SyncRun?> = runDao.observeById(id).map { it?.toDomain() }

    fun runsForTask(taskId: Long): Flow<List<SyncRun>> =
        runDao.observeForTask(taskId).map { rows -> rows.map { it.toDomain() } }

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

    /**
     * True once [taskId] has at least one SUCCESS run. A bisync task that has
     * never succeeded still needs rclone's `--resync` to establish its baseline;
     * the worker uses this to decide whether to request a resync.
     */
    suspend fun hasSucceeded(taskId: Long): Boolean = runDao.countSuccessful(taskId) > 0

    /**
     * Reconcile runs left RUNNING by a worker that died mid-run (process death /
     * force-stop, where the finally never executed). Marks them FAILED. Call once
     * at app startup. Returns the number of rows reconciled.
     */
    suspend fun reconcileInterruptedRuns(): Int =
        runDao.failInterruptedRuns(System.currentTimeMillis(), "Interrupted (app stopped mid-sync)")

    suspend fun clearAll() = runDao.deleteAll()
}
