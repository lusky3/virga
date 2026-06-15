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
import app.lusk.virga.core.common.model.SyncTask
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Schedules sync tasks with WorkManager. Periodic tasks honour the 15-minute
 * minimum interval; manual tasks are enqueued as one-shot work.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val taskRepository: SyncTaskRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Enqueues an immediate one-time sync of [taskId]. */
    fun syncNow(taskId: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_TASK_ID to taskId, SyncWorker.KEY_MANUAL to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            uniqueName(taskId) + "_now",
            // KEEP, not REPLACE: a second "Sync now" (double-tap, or "sync all" while
            // one is running) must NOT cancel the in-flight transfer and record it as
            // failed. If a manual sync is already queued/running for this task, keep it.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Enqueues an immediate one-time sync for every enabled task and returns how
     * many were enqueued, so a caller can message the user off the same single
     * read (no second flow collection that could disagree with what ran).
     */
    suspend fun syncAllEnabled(): Int {
        val enabled = taskRepository.tasks.first().filter { it.enabled }
        enabled.forEach { syncNow(it.id) }
        return enabled.size
    }

    /**
     * (Re)registers the schedule for a task, or cancels it if manual/disabled.
     * A calendar schedule (specific weekdays + time) takes precedence over a
     * plain interval. Calendar runs are one-shots re-enqueued after each run by
     * [SyncWorker], since WorkManager periodic work can't target a time-of-day.
     */
    fun schedule(task: SyncTask) {
        val interval = task.intervalMinutes
        when {
            !task.enabled -> cancel(task.id)
            task.scheduleDaysMask != 0 -> scheduleCalendar(task)
            interval != null -> schedulePeriodic(task, interval)
            else -> cancel(task.id)
        }
    }

    private fun schedulePeriodic(task: SyncTask, interval: Int) {
        val effectiveMinutes = interval.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            effectiveMinutes.toLong(), TimeUnit.MINUTES,
        )
            .setConstraints(task.toConstraints())
            .setInputData(workDataOf(SyncWorker.KEY_TASK_ID to task.id))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            uniqueName(task.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Enqueues the next calendar occurrence as a one-shot. [SyncWorker] calls
     * [schedule] again after the run to queue the following occurrence.
     */
    private fun scheduleCalendar(task: SyncTask) {
        val now = System.currentTimeMillis()
        val nextMs = SyncSchedule.nextOccurrenceMs(
            task.scheduleDaysMask, task.scheduleHour, task.scheduleMinute, now, ZoneId.systemDefault(),
        )
        if (nextMs <= now) {
            cancel(task.id)
            return
        }
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(nextMs - now, TimeUnit.MILLISECONDS)
            .setConstraints(task.toConstraints())
            .setInputData(workDataOf(SyncWorker.KEY_TASK_ID to task.id))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(
            uniqueName(task.id),
            // APPEND_OR_REPLACE, not REPLACE: a still-running calendar worker
            // re-enqueues its own next occurrence under THIS same unique name.
            // REPLACE would cancel the running worker before it can finalize
            // history / write back staged downloads / post its result, and a
            // watchdog heartbeat re-running scheduleCalendar would do the same to
            // any in-flight calendar sync. APPEND queues the next run behind the
            // current one; if none is running it behaves like a fresh enqueue.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun cancel(taskId: Long) {
        // Cancel BOTH unique-work names: the scheduled/periodic one and the
        // immediate "_now" one from syncNow(). A running sync is usually the
        // manual "_now" job, so cancelling only the bare name would leave it
        // running (the notification Cancel action + in-app cancel both rely on this).
        workManager.cancelUniqueWork(uniqueName(taskId))
        workManager.cancelUniqueWork(uniqueName(taskId) + "_now")
    }

    /**
     * Re-registers every scheduled task — used after device reboot and by the
     * watchdog heartbeat (every ~15 min). Skips any task whose unique work is
     * currently RUNNING: re-registering a calendar task mid-run would re-enqueue
     * it (APPEND_OR_REPLACE) or, for a heartbeat firing repeatedly, churn the
     * schedule under an in-flight sync. Reboot can't have a RUNNING worker, so
     * the skip is a no-op there.
     */
    suspend fun rescheduleAll() {
        taskRepository.scheduledTasks().forEach { task ->
            if (!isRunning(task.id)) schedule(task)
        }
    }

    /** True if either the scheduled or the "_now" unique work for [taskId] is RUNNING. */
    private fun isRunning(taskId: Long): Boolean {
        val names = listOf(uniqueName(taskId), uniqueName(taskId) + "_now")
        return names.any { name ->
            runCatching {
                workManager.getWorkInfosForUniqueWork(name).get()
                    .any { it.state == androidx.work.WorkInfo.State.RUNNING }
            }.getOrDefault(false)
        }
    }

    private fun SyncTask.toConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(requiresCharging)
        .setRequiresStorageNotLow(true)
        .build()

    private fun uniqueName(taskId: Long): String = SyncWorker.UNIQUE_PREFIX + taskId

    private companion object {
        const val MIN_INTERVAL_MINUTES = 15
    }
}
