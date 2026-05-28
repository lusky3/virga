package app.lusk.virga.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.SyncTaskEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules sync tasks with WorkManager. Periodic tasks honour the 15-minute
 * minimum interval; manual tasks are enqueued as one-shot work.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: SyncTaskRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Enqueues an immediate one-time sync of [taskId]. */
    fun syncNow(taskId: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_TASK_ID to taskId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            uniqueName(taskId) + "_now",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** (Re)registers the periodic schedule for a task, or cancels it if manual/disabled. */
    fun schedule(task: SyncTaskEntity) {
        val interval = task.intervalMinutes
        if (!task.enabled || interval == null) {
            cancel(task.id)
            return
        }
        val effectiveMinutes = interval.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            effectiveMinutes.toLong(), TimeUnit.MINUTES,
        )
            .setConstraints(task.toConstraints())
            .setInputData(workDataOf(SyncWorker.KEY_TASK_ID to task.id))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            uniqueName(task.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(taskId: Long) {
        workManager.cancelUniqueWork(uniqueName(taskId))
    }

    /** Re-registers every scheduled task — used after device reboot. */
    suspend fun rescheduleAll() {
        taskRepository.scheduledTasks().forEach(::schedule)
    }

    private fun SyncTaskEntity.toConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(requiresCharging)
        .setRequiresStorageNotLow(true)
        .build()

    private fun uniqueName(taskId: Long): String = SyncWorker.UNIQUE_PREFIX + taskId

    private companion object {
        const val MIN_INTERVAL_MINUTES = 15
    }
}
