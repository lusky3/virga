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
import app.lusk.virga.core.data.StatsRepository
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.rclone.RcloneEngine
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
    private val engine: RcloneEngine,
    private val taskRepository: SyncTaskRepository,
    private val historyRepository: SyncHistoryRepository,
    private val conflictRepository: ConflictRepository,
    private val statsRepository: StatsRepository,
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
            val earlyStartMs = System.currentTimeMillis()
            finishFailed(historyRepository.startRun(taskId), null, msg, direction = task.direction, runStartMs = earlyStartMs)
            runCatching {
                NotificationManagerCompat.from(applicationContext)
                    .notify(SyncNotifications.RESULT_NOTIFICATION_ID, notifications.error(task.name, msg, taskId))
            }
            return Result.failure()
        }

        // Mutual exclusion: if a sibling unique-work name for this task is already
        // RUNNING (a scheduled run while a manual "_now" run is in flight, or vice
        // versa), bail out — that run is handling it. Without this, two runs of the
        // same task share a staging dir and a delete-enabled mirror could see an
        // emptied source. Exclude this worker's own WorkInfo from the check.
        if (anotherRunInFlight(taskId)) {
            Log.w(TAG, "Skipping sync of task $taskId: another run is already in flight")
            return Result.success()
        }

        // setForeground promotes this worker to a foreground service. On Android 12+
        // a backgrounded, non-expedited worker can't legally start one, so this
        // throws ForegroundServiceStartNotAllowedException — the sync should still
        // run (just without the progress notification), never silently die.
        startForegroundQuietly(notifications.progress(task.name, null, taskId))

        val runStartMs = System.currentTimeMillis()
        val runId = historyRepository.startRun(taskId)
        val metered = applicationContext.getSystemService<ConnectivityManager>()
            ?.isActiveNetworkMetered ?: false

        // Per-run log (WS2.5): built from observed events, written on finish.
        val log = RunLogWriter(applicationContext.filesDir, runId)
        log.line("Sync started: ${task.name}")
        log.line("Direction: ${task.direction} · Mirror: ${task.deleteExtraneous}")
        log.line("Source: ${task.sourcePath}")
        log.line("Destination: ${task.remoteName}:${task.remotePath}")

        val staged = staging.prepare(task.sourcePath, task.direction, runId)
        val effectiveTask = if (staged.isStaged) task.copy(sourcePath = staged.localPath) else task

        var last: SyncProgress? = null
        var lastNotifiedPct = -1
        var failure: Throwable? = null
        // Reference-counted daemon lease: a concurrent sync ("sync all" / co-scheduled
        // tasks) must not have its daemon torn down by THIS worker's cleanup. Released
        // only if successfully acquired.
        var leased = false

        // One-way syncs are ADDITIVE (rclone `copy`) by default: they never delete
        // files on the destination. Mirroring (rclone `sync`, delete-extraneous)
        // makes the destination identical to the source — catastrophic if misused
        // (uploading a couple of local files to a remote folder of hundreds would
        // delete the rest). It is therefore an explicit per-task opt-in
        // (Mirror toggle, WS2.2) that the editor gates behind an acknowledgement.
        // (BISYNC reconciles deletions through its own two-way logic, ignoring this.)
        val allowDeletes = task.deleteExtraneous

        try {
            // Lease the shared daemon for the lifetime of this sync (released in finally).
            engine.acquireDaemon()
            leased = true
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
                    .collect { progress ->
                        // Record EVERY emit + publish to the UI: the terminal emit carries
                        // the authoritative final counts. (A flow-level distinctUntilChanged
                        // on byte-percent used to drop that terminal emit — it shares 100%
                        // with the prior tick, which lands before rclone finalizes the
                        // `transfers` count — so finished syncs recorded 0 files moved.)
                        last = progress
                        setProgress(SyncProgressData.encode(progress))
                        // Throttle ONLY the foreground notification + log to once per
                        // integer-percent change (Android rate-limits frequent FGS updates).
                        val pct = (progress.fraction * 100).toInt()
                        if (pct != lastNotifiedPct) {
                            lastNotifiedPct = pct
                            log.line(
                                "Progress $pct% · " +
                                    "${progress.transferredFiles}/${progress.totalFiles} files · " +
                                    "${progress.errors} error(s)",
                            )
                            startForegroundQuietly(notifications.progress(task.name, progress, taskId))
                        }
                    }
                }
            }
            // For staged downloads, copy rclone's output back into the SAF tree
            // BEFORE the finally runs staging.cleanup() (which recursively deletes
            // the staged dir). Doing it in the epilogue let cleanup wipe the dir
            // first, so writeBack copied nothing and the run was still recorded
            // SUCCESS — silent data loss. A write-back failure becomes the run's
            // failure so the epilogue records FAILED + notifies + returns failure.
            if (failure == null && staged.isStaged && task.direction == SyncDirection.DOWNLOAD) {
                runCatching { staging.writeBack(staged) }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    failure = VirgaError.Storage(e.message ?: "Failed to write back to folder")
                }
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            // The worker was cancelled (user cancel, WorkManager stop, or scheduler
            // REPLACE). Record the run as CANCELLED before rethrowing — under
            // NonCancellable so this finalization isn't itself cancelled — otherwise
            // it stays stuck RUNNING. Cancellation must still propagate so
            // WorkManager can stop the worker cleanly.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { finishCancelled(runId, last, task.direction, runStartMs) }
            }
            throw t
        } catch (t: Throwable) {
            // A direct throw in the orchestration body — e.g. listRemotes() in the
            // local-remote guard — must be recorded as a failure, not escape past
            // the finally and leave the run stuck in RUNNING forever.
            failure = t
        } finally {
            // Cleanup must survive cancellation: releaseDaemon()/cleanup() suspend
            // (withContext(io)), which would no-op on an already-cancelled job and
            // leak the daemon + plaintext config lease and the staged cache dir.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                if (leased) runCatching { engine.releaseDaemon() }
                runCatching { staging.cleanup(staged) }
            }
        }

        // Bound the unbounded sync_runs table: drop runs older than the retention window.
        runCatching { historyRepository.pruneOlderThan(System.currentTimeMillis() - RUN_RETENTION_MS) }

        val result = if (failure == null) {
            log.line("Completed: ${last?.transferredFiles ?: 0} file(s) transferred")
            finishSucceeded(runId, last, log.path.takeIf { log.flush() }, task.direction, runStartMs)
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
            finishFailed(runId, last, msg, log.path.takeIf { log.flush() }, task.direction, runStartMs)
            runCatching {
                NotificationManagerCompat.from(applicationContext)
                    .notify(SyncNotifications.RESULT_NOTIFICATION_ID, notifications.error(task.name, msg, taskId))
            }
            // Transient transport problems are worth retrying; everything else fails.
            if (failure is VirgaError.Network) Result.retry() else Result.failure()
        }

        // LAST statement: a calendar schedule runs as a one-shot, so queue the NEXT
        // occurrence now that this run is fully finalized (regardless of outcome).
        // Done after the result is determined and history is written so the
        // re-enqueue (APPEND_OR_REPLACE) can't race this still-RUNNING worker and
        // cancel its history/notification. Interval/periodic tasks repeat on their
        // own and don't need re-enqueuing here.
        if (task.scheduleDaysMask != 0 && task.enabled) {
            runCatching { scheduler.schedule(task) }
        }
        return result
    }

    /** Record a SUCCESS run from the last observed [progress] snapshot. */
    private suspend fun finishSucceeded(
        runId: Long,
        progress: SyncProgress?,
        logPath: String? = null,
        direction: SyncDirection,
        runStartMs: Long,
    ) {
        historyRepository.finishRun(
            runId = runId,
            status = SyncStatus.SUCCESS,
            filesTransferred = progress?.transferredFiles ?: 0,
            bytesTransferred = progress?.bytesTransferred ?: 0L,
            errorCount = progress?.errors ?: 0,
            logPath = logPath,
        )
        val finishedAt = System.currentTimeMillis()
        runCatching {
            statsRepository.recordRun(
                direction = direction,
                bytesTransferred = progress?.bytesTransferred ?: 0L,
                filesTransferred = progress?.transferredFiles ?: 0,
                success = true,
                durationMs = maxOf(0L, finishedAt - runStartMs),
                finishedAtEpochMs = finishedAt,
            )
        }.onFailure { Log.w(TAG, "Failed to record lifetime stats for successful run", it) }
    }

    /** Record a FAILED run (+1 error) with [message] from the last [progress] snapshot. */
    private suspend fun finishFailed(
        runId: Long,
        progress: SyncProgress?,
        message: String,
        logPath: String? = null,
        direction: SyncDirection,
        runStartMs: Long,
    ) {
        historyRepository.finishRun(
            runId = runId,
            status = SyncStatus.FAILED,
            filesTransferred = progress?.transferredFiles ?: 0,
            bytesTransferred = progress?.bytesTransferred ?: 0L,
            errorCount = (progress?.errors ?: 0) + 1,
            errorMessage = message,
            logPath = logPath,
        )
        val finishedAt = System.currentTimeMillis()
        runCatching {
            statsRepository.recordRun(
                direction = direction,
                bytesTransferred = progress?.bytesTransferred ?: 0L,
                filesTransferred = progress?.transferredFiles ?: 0,
                success = false,
                durationMs = maxOf(0L, finishedAt - runStartMs),
                finishedAtEpochMs = finishedAt,
            )
        }.onFailure { Log.w(TAG, "Failed to record lifetime stats for failed run", it) }
    }

    /** Record a CANCELLED run from the last observed [progress] snapshot. */
    private suspend fun finishCancelled(
        runId: Long,
        progress: SyncProgress?,
        direction: SyncDirection,
        runStartMs: Long,
    ) {
        historyRepository.finishRun(
            runId = runId,
            status = SyncStatus.CANCELLED,
            filesTransferred = progress?.transferredFiles ?: 0,
            bytesTransferred = progress?.bytesTransferred ?: 0L,
            errorCount = progress?.errors ?: 0,
        )
        val finishedAt = System.currentTimeMillis()
        runCatching {
            statsRepository.recordRun(
                direction = direction,
                bytesTransferred = progress?.bytesTransferred ?: 0L,
                filesTransferred = progress?.transferredFiles ?: 0,
                success = false,
                durationMs = maxOf(0L, finishedAt - runStartMs),
                finishedAtEpochMs = finishedAt,
            )
        }.onFailure { Log.w(TAG, "Failed to record lifetime stats for cancelled run", it) }
    }

    /**
     * Promote to a foreground service, tolerating the OS refusing it. On Android
     * 12+ a backgrounded, non-expedited worker can't start an FGS
     * ([ForegroundServiceStartNotAllowedException]); older guards may also throw
     * [IllegalStateException]. Either way the sync should continue without the
     * progress notification rather than failing or aborting a healthy transfer.
     */
    private suspend fun startForegroundQuietly(notification: android.app.Notification) {
        try {
            setForeground(foregroundInfo(notification))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException is API 31+; catch broadly so
            // older API levels (where the class isn't loaded) still compile and run.
            Log.w(TAG, "Could not start foreground service; continuing without it", e)
        }
    }

    /**
     * True if a sibling unique-work name for [taskId] is RUNNING in a worker other
     * than this one. Guards against the scheduled and manual ("_now") runs of the
     * same task executing concurrently (shared staging dir, mirror data loss).
     */
    private fun anotherRunInFlight(taskId: Long): Boolean {
        val names = listOf(
            UNIQUE_PREFIX + taskId,
            UNIQUE_PREFIX + taskId + "_now",
        )
        val workManager = androidx.work.WorkManager.getInstance(applicationContext)
        return names.any { name ->
            runCatching {
                workManager.getWorkInfosForUniqueWork(name).get().any { info ->
                    info.state == androidx.work.WorkInfo.State.RUNNING && info.id != id
                }
            }.getOrDefault(false)
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
        private const val TAG = "SyncWorker"
        // Retain ~30 days of sync history; older runs are pruned each invocation.
        private const val RUN_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
