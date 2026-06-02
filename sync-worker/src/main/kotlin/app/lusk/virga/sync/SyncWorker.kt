package app.lusk.virga.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.ConflictRepository
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.rclone.RcloneEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChangedBy

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
    private val engine: RcloneEngine,
    private val taskRepository: SyncTaskRepository,
    private val historyRepository: SyncHistoryRepository,
    private val conflictRepository: ConflictRepository,
    private val staging: LocalStaging,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(appContext, params) {

    private val notifications = SyncNotifications(appContext)

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0) return Result.failure()
        val task = taskRepository.getTask(taskId) ?: run {
            // The task was deleted (e.g. its remote was removed) but periodic work
            // for it may still be scheduled. Cancel it so this zombie stops firing,
            // and succeed (a failure would just be retried).
            scheduler.cancel(taskId)
            return Result.success()
        }

        // SAF sources cannot bisync: rclone needs read/write access to a real path
        // on both sides, which staging cannot provide symmetrically.
        if (task.sourcePath.startsWith("content://") && task.direction == SyncDirection.BISYNC) {
            val msg = "Two-way sync isn't supported for this folder on this device."
            finishFailed(historyRepository.startRun(taskId), null, msg)
            runCatching {
                NotificationManagerCompat.from(applicationContext)
                    .notify(SyncNotifications.RESULT_NOTIFICATION_ID, notifications.error(task.name, msg, taskId))
            }
            return Result.failure()
        }

        setForeground(foregroundInfo(notifications.progress(task.name, null, taskId)))

        val runId = historyRepository.startRun(taskId)
        val metered = applicationContext.getSystemService<ConnectivityManager>()
            ?.isActiveNetworkMetered ?: false

        // Per-run log (WS2.5): built from observed events, written on finish.
        val log = RunLogWriter(applicationContext.filesDir, runId)
        log.line("Sync started: ${task.name}")
        log.line("Direction: ${task.direction} · Mirror: ${task.deleteExtraneous}")
        log.line("Source: ${task.sourcePath}")
        log.line("Destination: ${task.remoteName}:${task.remotePath}")

        val staged = staging.prepare(task.sourcePath, task.direction)
        val effectiveTask = if (staged.isStaged) task.copy(sourcePath = staged.localPath) else task

        var last: SyncProgress? = null
        var failure: Throwable? = null

        // One-way syncs are ADDITIVE (rclone `copy`) by default: they never delete
        // files on the destination. Mirroring (rclone `sync`, delete-extraneous)
        // makes the destination identical to the source — catastrophic if misused
        // (uploading a couple of local files to a remote folder of hundreds would
        // delete the rest). It is therefore an explicit per-task opt-in
        // (Mirror toggle, WS2.2) that the editor gates behind an acknowledgement.
        // (BISYNC reconciles deletions through its own two-way logic, ignoring this.)
        val allowDeletes = task.deleteExtraneous

        try {
            // Guard: the SAF source folder couldn't be read (its persisted access
            // permission was lost). Staging is empty, so proceeding with an upload
            // mirror would DELETE the cloud destination. Fail loudly and tell the
            // user to re-select the folder instead.
            val stagedUploadLike = staged.isStaged &&
                (task.direction == SyncDirection.UPLOAD || task.direction == SyncDirection.BISYNC)
            if (stagedUploadLike && !staged.sourceReadable) {
                failure = VirgaError.Storage(
                    "Can't read the selected folder — re-select it for this task (its access permission was lost).",
                )
            } else if (stagedUploadLike && !staged.fullyStaged && (allowDeletes || task.direction == SyncDirection.BISYNC)) {
                // Some source files couldn't be staged. Running a delete-enabled mirror
                // (or bisync) against the incomplete copy would delete their counterparts
                // on the cloud destination — abort instead of risking data loss.
                failure = VirgaError.Storage(
                    "Couldn't read every file in the selected folder; stopping so the mirror doesn't delete files on the cloud. Check the folder's access and try again.",
                )
            } else {
                // Guard: a 'local'-type rclone remote points at a real filesystem path,
                // which the rclone child process can't reach under scoped storage (no
                // all-files access). Fail with a clear message instead of an opaque
                // rclone error. listRemotes() reuses the same daemon the sync starts.
                val remoteType = engine.listRemotes().firstOrNull { it.name == task.remoteName }?.type
                if (remoteType == "local" && !Environment.isExternalStorageManager()) {
                    failure = VirgaError.Rclone(
                        message = "Local-disk remotes need all-files access, which isn't granted on this build. Use a cloud remote.",
                    )
                } else {
                // A bisync task's first run needs rclone's --resync to establish
                // the baseline listing (otherwise rclone aborts). Request it until
                // the task has a prior successful run. The current run is still
                // RUNNING here, so it doesn't count itself.
                val resync = task.direction == SyncDirection.BISYNC &&
                    !historyRepository.hasSucceeded(taskId)
                executor.run(effectiveTask, metered, allowDeletes, resync = resync)
                    .catch { failure = it }
                    // Only update the foreground notification when the integer percent changes.
                    .distinctUntilChangedBy { p -> (p.fraction * 100).toInt() }
                    .collect { progress ->
                        last = progress
                        // Publish to the UI (WS1.1) and mirror to the notification.
                        setProgress(SyncProgressData.encode(progress))
                        log.line(
                            "Progress ${(progress.fraction * 100).toInt()}% · " +
                                "${progress.transferredFiles}/${progress.totalFiles} files · " +
                                "${progress.errors} error(s)",
                        )
                        setForeground(foregroundInfo(notifications.progress(task.name, progress, taskId)))
                    }
                }
            }
        } catch (t: Throwable) {
            // A direct throw in the orchestration body — e.g. listRemotes() in the
            // local-remote guard — must be recorded as a failure, not escape past
            // the finally and leave the run stuck in RUNNING forever. Cancellation
            // must still propagate so WorkManager can stop the worker cleanly.
            if (t is kotlinx.coroutines.CancellationException) throw t
            failure = t
        } finally {
            runCatching { engine.stopDaemon() }
            runCatching { staging.cleanup(staged) }
        }

        // Bound the unbounded sync_runs table: drop runs older than the retention window.
        runCatching { historyRepository.pruneOlderThan(System.currentTimeMillis() - RUN_RETENTION_MS) }

        // A calendar schedule runs as a one-shot, so queue the NEXT occurrence now
        // that this run is finished (regardless of outcome). Interval/periodic
        // tasks repeat on their own, so they don't need re-enqueuing here.
        if (task.scheduleDaysMask != 0 && task.enabled) {
            runCatching { scheduler.schedule(task) }
        }

        return if (failure == null) {
            // For staged downloads, copy rclone's output back into the SAF tree.
            if (staged.isStaged && task.direction == SyncDirection.DOWNLOAD) {
                val writeResult = runCatching { staging.writeBack(staged) }
                if (writeResult.isFailure) {
                    val msg = writeResult.exceptionOrNull()?.message ?: "Failed to write back to folder"
                    log.line("Failed: $msg")
                    log.flush()
                    finishFailed(runId, last, msg, log.path)
                    runCatching {
                        NotificationManagerCompat.from(applicationContext)
                            .notify(SyncNotifications.RESULT_NOTIFICATION_ID, notifications.error(task.name, msg, taskId))
                    }
                    return Result.failure()
                }
            }
            log.line("Completed: ${last?.transferredFiles ?: 0} file(s) transferred")
            log.flush()
            finishSucceeded(runId, last, log.path)
            // After a bisync, scan the destination for rclone conflict files
            // and queue them for user resolution.
            if (task.direction == SyncDirection.BISYNC) {
                // detectFor returns Result<Int>; surface both a thrown error and a
                // returned failure instead of silently reporting a clean sync.
                runCatching { conflictRepository.detectFor(task) }
                    .onFailure { Log.w(TAG, "conflict detection threw", it) }
                    .getOrNull()
                    ?.onFailure { Log.w(TAG, "conflict detection failed", it) }
            }
            Result.success()
        } else {
            val msg = failure.message ?: "Sync failed"
            log.line("Failed: $msg")
            log.flush()
            finishFailed(runId, last, msg, log.path)
            runCatching {
                NotificationManagerCompat.from(applicationContext)
                    .notify(SyncNotifications.RESULT_NOTIFICATION_ID, notifications.error(task.name, msg, taskId))
            }
            // Transient transport problems are worth retrying; everything else fails.
            if (failure is VirgaError.Network) Result.retry() else Result.failure()
        }
    }

    /** Record a SUCCESS run from the last observed [progress] snapshot. */
    private suspend fun finishSucceeded(
        runId: Long,
        progress: SyncProgress?,
        logPath: String? = null,
    ) =
        historyRepository.finishRun(
            runId = runId,
            status = SyncStatus.SUCCESS,
            filesTransferred = progress?.transferredFiles ?: 0,
            bytesTransferred = progress?.bytesTransferred ?: 0L,
            errorCount = progress?.errors ?: 0,
            logPath = logPath,
        )

    /** Record a FAILED run (+1 error) with [message] from the last [progress] snapshot. */
    private suspend fun finishFailed(
        runId: Long,
        progress: SyncProgress?,
        message: String,
        logPath: String? = null,
    ) = historyRepository.finishRun(
        runId = runId,
        status = SyncStatus.FAILED,
        filesTransferred = progress?.transferredFiles ?: 0,
        bytesTransferred = progress?.bytesTransferred ?: 0L,
        errorCount = (progress?.errors ?: 0) + 1,
        errorMessage = message,
        logPath = logPath,
    )

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
        private const val TAG = "SyncWorker"
        // Retain ~30 days of sync history; older runs are pruned each invocation.
        private const val RUN_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
