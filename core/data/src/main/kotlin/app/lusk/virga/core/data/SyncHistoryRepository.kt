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

    /**
     * Finalizes the run [runId]. Uses a targeted UPDATE so the `startedAtEpochMs`
     * recorded by [startRun] is preserved (the caller no longer supplies it, which
     * previously overwrote it with a slightly-later timestamp).
     *
     * [failedFiles] is a newline-joined list of "path\terror" entries (tab-separated),
     * capped at 100 by the caller. Defaults to empty (no file-level failures).
     *
     * [remoteName] and [direction] are stamped on the row for aggregate stats queries.
     * [startedAtEpochMs] is used to compute [durationMs]; pass 0 to leave it unset.
     */
    suspend fun finishRun(
        runId: Long,
        status: SyncStatus,
        filesTransferred: Int,
        bytesTransferred: Long,
        errorCount: Int,
        errorMessage: String? = null,
        logPath: String? = null,
        failedFiles: String = "",
        remoteName: String = "",
        direction: String = "",
        startedAtEpochMs: Long = 0,
    ) {
        val endedAt = System.currentTimeMillis()
        runDao.finishRun(
            runId = runId,
            endedAtEpochMs = endedAt,
            status = status,
            filesTransferred = filesTransferred,
            bytesTransferred = bytesTransferred,
            errorCount = errorCount,
            errorMessage = errorMessage,
            logPath = logPath,
            failedFiles = failedFiles,
            remoteName = remoteName,
            direction = direction,
            durationMs = if (startedAtEpochMs > 0) maxOf(0L, endedAt - startedAtEpochMs) else 0,
        )
    }

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
     * at app startup, passing [startedBeforeEpochMs] = the process-start time, so a
     * worker that legitimately started a fresh run *during* startup (WorkManager can
     * resume workers before Application.onCreate finishes) is NOT mis-marked FAILED.
     * Returns the number of rows reconciled.
     */
    suspend fun reconcileInterruptedRuns(startedBeforeEpochMs: Long): Int =
        runDao.failInterruptedRuns(
            now = System.currentTimeMillis(),
            message = "Interrupted (app stopped mid-sync)",
            startedBefore = startedBeforeEpochMs,
        )

    suspend fun clearAll() = runDao.deleteAll()
}
