package app.lusk.virga.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.catch

/**
 * Executes one sync task as a foreground (dataSync) job so it survives Doze and
 * backgrounding. Progress is mirrored to the notification; the outcome is
 * recorded in [SyncHistoryRepository].
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val executor: SyncExecutor,
    private val taskRepository: SyncTaskRepository,
    private val historyRepository: SyncHistoryRepository,
) : CoroutineWorker(appContext, params) {

    private val notifications = SyncNotifications(appContext)

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0) return Result.failure()
        val task = taskRepository.getTask(taskId) ?: return Result.failure()

        setForeground(foregroundInfo(notifications.progress(task.name, null)))

        val startedAt = System.currentTimeMillis()
        val runId = historyRepository.startRun(taskId)
        val metered = applicationContext.getSystemService<ConnectivityManager>()
            ?.isActiveNetworkMetered ?: false

        var last: SyncProgress? = null
        var failure: Throwable? = null

        executor.run(task, metered)
            .catch { failure = it }
            .collect { progress ->
                last = progress
                setForeground(foregroundInfo(notifications.progress(task.name, progress)))
            }

        return if (failure == null) {
            historyRepository.finishRun(
                runId = runId,
                taskId = taskId,
                startedAtEpochMs = startedAt,
                status = SyncStatus.SUCCESS,
                filesTransferred = last?.transferredFiles ?: 0,
                bytesTransferred = last?.bytesTransferred ?: 0L,
                errorCount = last?.errors ?: 0,
            )
            Result.success()
        } else {
            val message = failure?.message ?: "Sync failed"
            historyRepository.finishRun(
                runId = runId,
                taskId = taskId,
                startedAtEpochMs = startedAt,
                status = SyncStatus.FAILED,
                filesTransferred = last?.transferredFiles ?: 0,
                bytesTransferred = last?.bytesTransferred ?: 0L,
                errorCount = (last?.errors ?: 0) + 1,
                errorMessage = message,
            )
            // Transient transport problems are worth retrying; everything else fails.
            if (failure is VirgaError.Network) Result.retry() else Result.failure()
        }
    }

    private fun foregroundInfo(notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SyncNotifications.FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SyncNotifications.FOREGROUND_NOTIFICATION_ID, notification)
        }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val UNIQUE_PREFIX = "sync_task_"
    }
}
