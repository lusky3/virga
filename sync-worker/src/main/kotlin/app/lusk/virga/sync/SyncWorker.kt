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
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.ConflictRepository
import app.lusk.virga.core.data.ConflictType
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.StatsRepository
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.rclone.isAuthError
import app.lusk.virga.core.rclone.RcloneEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * Executes one sync task as a foreground (dataSync) job so it survives Doze and
 * backgrounding. Progress is mirrored to the notification; the outcome is
 * recorded in [SyncHistoryRepository].
 */
@HiltWorker
// `open` so unit tests can override the WorkManager-backed [anotherRunInFlight]
// seam without standing up a live WorkManager; production behaviour is unchanged.
// LongParameterList is suppressed: every constructor arg is an @AssistedInject DI
// dependency (a known detekt false-positive for injected constructors), not a
// hand-passed parameter list — and Codacy runs detekt with no ignoreAnnotated config.
@Suppress("LongParameterList")
open class SyncWorker @AssistedInject constructor(
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
    private val remoteRepository: RemoteRepository,
    private val preferencesRepository: PreferencesRepository,
    private val checkUseCase: CheckUseCase,
    private val sourceHealthCheck: SourceHealthCheck,
) : CoroutineWorker(appContext, params) {

    private val notifications = SyncNotifications(appContext)

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0) return Result.failure()
        // A manual ("_now") run must never re-arm the calendar: the scheduled
        // worker owns that cadence, so re-arming from a manual run would append a
        // duplicate next occurrence (APPEND_OR_REPLACE) alongside the scheduled one.
        val isManualRun = inputData.getBoolean(KEY_MANUAL, false)
        val task = taskRepository.getTask(taskId) ?: run {
            // The task was deleted (e.g. its remote was removed) but periodic work
            // for it may still be scheduled. Cancel it so this zombie stops firing,
            // and succeed (a failure would just be retried).
            scheduler.cancel(taskId)
            return Result.success()
        }

        // Quiet-hours check: for SCHEDULED runs only, suppress the sync when the
        // current time falls inside the global blackout window. Manual "Sync now"
        // always bypasses quiet hours — user intent. We re-enqueue the calendar
        // next occurrence (which the scheduler will shift past the window), so the
        // schedule is not lost. Return success so WorkManager doesn't retry/backoff.
        if (!isManualRun && isWithinQuietHours()) {
            Log.i(TAG, "Deferred for quiet hours: task $taskId — skipping sync, next occurrence re-queued.")
            reenqueueCalendar(task, isManualRun)
            return Result.success()
        }

        // Detect metered status early so cap check can use it before startForeground.
        val metered = applicationContext.getSystemService<ConnectivityManager>()
            ?.isActiveNetworkMetered ?: false

        // Metered data cap: skip if the monthly metered total >= cap (enforced for all runs
        // including manual — the cap is a safety limit, not a soft preference).
        if (metered && isMeteredCapReached()) {
            Log.i(TAG, "Skipped: monthly metered data cap reached for task $taskId")
            reenqueueCalendar(task, isManualRun)
            return Result.success()
        }

        // SAF sources cannot bisync: rclone needs read/write access to a real path
        // on both sides, which staging cannot provide symmetrically.
        if (task.sourcePath.startsWith("content://") && task.direction == SyncDirection.BISYNC) {
            val msg = "Two-way sync isn't supported for this folder on this device."
            val earlyStartMs = System.currentTimeMillis()
            finishFailed(historyRepository.startRun(taskId), null, msg, direction = task.direction, runStartMs = earlyStartMs, remoteName = task.remoteName, metered = metered)
            runCatching {
                NotificationManagerCompat.from(applicationContext)
                    .notify(SyncNotifications.resultId(taskId), notifications.error(task.name, msg, taskId))
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
            // Bailing here must still arm the next calendar occurrence — otherwise a
            // calendar-scheduled run that skips drops its own re-enqueue, and that
            // occurrence silently never repeats. The re-arm is gated to the scheduled
            // worker (isManualRun = false): if the manual "_now" run also re-armed, the
            // TOCTOU window where both skip would queue TWO next occurrences. With the
            // gate, only the scheduled worker re-arms, so skipping is safe AND single.
            // (The residual TOCTOU race that lets both runs skip the actual sync is
            // accepted for now; the full DB-lease redesign is out of scope.)
            reenqueueCalendar(task, isManualRun)
            return Result.success()
        }

        // setForeground promotes this worker to a foreground service. On Android 12+
        // a backgrounded, non-expedited worker can't legally start one, so this
        // throws ForegroundServiceStartNotAllowedException — the sync should still
        // run (just without the progress notification), never silently die.
        startForegroundQuietly(taskId, notifications.progress(task.name, null, taskId))

        val runStartMs = System.currentTimeMillis()
        val runId = historyRepository.startRun(taskId)

        // Per-run log (WS2.5): built from observed events, written on finish.
        val log = RunLogWriter(applicationContext.filesDir, runId)
        log.line("Sync started: ${task.name}")
        log.line("Direction: ${task.direction} · Mirror: ${task.deleteExtraneous} · Move: ${task.deleteSource}")
        log.line("Source: ${task.sourcePath}")
        log.line("Destination: ${task.remoteName}:${task.remotePath}")

        var last: SyncProgress? = null
        var lastNotifiedPct = -1
        var failure: Throwable? = null

        // Sample-read preflight (Phase 3): before staging a SAF upload/bisync, probe a
        // few files under a tight timeout. A timeout/unreadable result is a strong "the
        // card is failing" signal, so abort BEFORE the expensive staging copy + daemon
        // lease rather than starting a doomed, minutes-long run. Setting [failure] here
        // makes staging skip and the run fall through to the failure epilogue, where it
        // is RECORDED (not swallowed). content:// + UPLOAD/BISYNC only; other sources
        // and downloads pass straight through (probe() short-circuits to OK).
        if (task.sourcePath.startsWith("content://") &&
            (task.direction == SyncDirection.UPLOAD || task.direction == SyncDirection.BISYNC)
        ) {
            val health = sourceHealthCheck.probe(task.sourcePath)
            preflightFailureMessage(health)?.let { warning ->
                log.line(warning)
                if (health != SourceHealthCheck.HealthResult.OK) {
                    failure = VirgaError.Storage(warning)
                }
            }
        }

        // Only stage when the preflight passed: a failed probe must not trigger the
        // doomed staging copy. A non-staged default keeps `staged` non-null so the
        // finally's cleanup() (a no-op for cacheDir == null) and the downstream
        // isStaged guards stay safe.
        val staged = if (failure == null) {
            staging.prepare(task.sourcePath, task.direction, runId)
        } else {
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        }
        val effectiveTask = if (staged.isStaged) task.copy(sourcePath = staged.localPath) else task
        stagingTimeoutWarning(staged.readTimeouts)?.let { log.line(it) }
        // Captured inside the daemon lease (after the sync flow) when errorCount > 0.
        // Capped at FAILED_FILES_CAP entries (100) to bound storage. Stored as
        // newline-joined "path\terror" lines for persistence in SyncRunEntity.failedFiles.
        var failedFiles = ""
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
        // Move mode (rclone `sync/move`, delete-source) deletes the source after a
        // successful transfer; also an explicit opt-in gated behind an acknowledgement.
        // (BISYNC reconciles deletions through its own two-way logic, ignoring both.)
        // Normalize the destructive flags at EXECUTION time, not just in the editor:
        // a malformed persisted task (a content:// source, BISYNC, or both delete
        // flags set) must never reach destructive routing. Move is forbidden for SAF
        // sources (rclone can't delete from a staged copy) and for BISYNC (two-way
        // reconciliation owns deletions), and it takes precedence over mirror-delete.
        val allowMove = task.deleteSource &&
            task.direction != SyncDirection.BISYNC &&
            !task.sourcePath.startsWith("content://")
        val allowDeletes = task.deleteExtraneous && !allowMove

        try {
          // Skip the whole sync body when the preflight already failed: the failure
          // is recorded by the epilogue below. Guarding here (rather than staging the
          // copy + leasing the daemon for a doomed run) is the point of the preflight.
          if (failure == null) {
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
                // B7: pre-sync conflict detection for one-way tasks (DETECTION-ONLY).
                // Runs a check before the actual sync and records advisory conflicts so
                // the user sees them in ConflictsScreen. Does NOT change the sync outcome.
                runOneWayConflictCheck(task, effectiveTask)

                // A bisync task's first run needs rclone's --resync to establish
                // the baseline listing (otherwise rclone aborts). Request it until
                // the task has a prior successful run. The current run is still
                // RUNNING here, so it doesn't count itself.
                val resync = task.direction == SyncDirection.BISYNC &&
                    !historyRepository.hasSucceeded(taskId)
                executor.run(effectiveTask, metered, allowDeletes, allowMove, resync = resync)
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
                            startForegroundQuietly(taskId, notifications.progress(task.name, progress, taskId))
                        }
                    }
                }
            }
          }
            // Capture per-file failures while the daemon lease is still held. Only
            // attempted on a partial-success run (errorCount > 0, no fatal failure):
            // a fatal abort has no meaningful per-file list. Cap at FAILED_FILES_CAP
            // (100) to bound DB storage; log a note if the list was truncated.
            val errorCount = last?.errors ?: 0
            val statsGroup = last?.statsGroup
            if (failure == null && errorCount > 0 && statsGroup != null) {
                failedFiles = captureFailedFiles(statsGroup, log)
            }
            failedFiles = mergeStalledFile(failedFiles, last?.stalledFile)

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
                runCatching { finishCancelled(runId, last, task.direction, runStartMs, task.remoteName, metered) }
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
        // One shared cutoff so the DB rows and their on-disk log files prune together.
        val pruneBefore = System.currentTimeMillis() - RUN_RETENTION_MS
        runCatching { historyRepository.pruneOlderThan(pruneBefore) }
            .onFailure { Log.w(TAG, "Failed to prune old sync runs", it) }
        runCatching { RunLogWriter.pruneOlderThan(applicationContext.filesDir, pruneBefore) }
            .onFailure { Log.w(TAG, "Failed to prune old run logs", it) }

        val result = if (failure == null) {
            // A COPY/backup run that hit file-level errors (a file couldn't be read or
            // transferred) is a PARTIAL SUCCESS: rclone copied the rest and continued, so
            // the run is recorded SUCCESS with a non-zero errorCount — not FAILED. rclone's
            // RC stats give an error COUNT, not a per-file list, so the summary is
            // count-based; the per-file list is captured above (failedFiles) from
            // core/transferred. The mirror/bisync paths still fail hard (handled upstream).
            val finalErrorCount = last?.errors ?: 0
            if (finalErrorCount > 0) {
                log.line(
                    "Completed with errors: ${last?.transferredFiles ?: 0} transferred, " +
                        "$finalErrorCount file(s) could not be read/transferred — see entries above.",
                )
            } else {
                log.line("Completed: ${last?.transferredFiles ?: 0} file(s) transferred")
            }
            finishSucceeded(runId, last, log.path.takeIf { log.flush() }, task.direction, runStartMs, failedFiles, task.remoteName, metered)
            // After a bisync, scan the destination for rclone conflict files
            // and queue them for user resolution. detectFor → engine.listDir needs
            // a daemon; the worker's own lease was already released in the finally
            // above (which stopped the daemon + re-encrypted the config), so acquire
            // a fresh lease here and release it (under NonCancellable, matching the
            // cleanup pattern) — otherwise ensureDaemon() would start an orphan,
            // unleased rclone process that nothing ever tears down.
            if (task.direction == SyncDirection.BISYNC) {
                var conflictLeased = false
                try {
                    engine.acquireDaemon()
                    conflictLeased = true
                    // detectFor returns Result<Int>; surface both a thrown error and a
                    // returned failure instead of silently reporting a clean sync.
                    runCatching { conflictRepository.detectFor(task) }
                        .onFailure { Log.w(TAG, "conflict detection threw", it) }
                        .getOrNull()
                        ?.onFailure { Log.w(TAG, "conflict detection failed", it) }
                } finally {
                    if (conflictLeased) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            runCatching { engine.releaseDaemon() }
                        }
                    }
                }
            }
            // Post the result notification. On a clean run, a quiet "Sync complete" so the
            // foreground notification doesn't silently vanish. On a partial success
            // (errors>0), the error builder instead — it carries the error count and a Retry
            // action, so the user isn't told the backup was clean when some files were skipped.
            // D5: when notifyOnFailureOnly is on, skip the success notification only — error
            // and partial-success (finalErrorCount > 0) paths always post so failures are
            // never silenced. The FGS/progress notification is managed separately and is
            // never affected by this preference.
            runCatching {
                val suppressSuccess = finalErrorCount == 0 &&
                    runCatching { preferencesRepository.preferences.first().notifyOnFailureOnly }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                        .getOrDefault(false)
                if (!suppressSuccess) {
                    val notification = if (finalErrorCount > 0) {
                        notifications.error(
                            task.name,
                            "Completed with $finalErrorCount error(s) — some files couldn't be read or transferred.",
                            taskId,
                        )
                    } else {
                        notifications.complete(task.name, last?.transferredFiles ?: 0, taskId)
                    }
                    NotificationManagerCompat.from(applicationContext)
                        .notify(SyncNotifications.resultId(taskId), notification)
                }
            }
            // Belt-and-suspenders: a successful sync proves the token is valid.
            // Clear any lingering needsReauth flag so re-auth via an out-of-band
            // token fix (e.g. rclone config on another machine) auto-recovers.
            runCatching { remoteRepository.setNeedsReauth(task.remoteName, false) }
                .onFailure { Log.w(TAG, "Failed to clear needsReauth for ${task.remoteName}", it) }
            Result.success()
        } else {
            val sourceIsLocal = !task.sourcePath.startsWith("content://") || staged.isStaged
            val msg = (failure as? VirgaError.Stall)
                ?.let { stallUserMessage(it, task.direction, sourceIsLocal) }
                ?: failure.message ?: "Sync failed"
            log.line("Failed: $msg")
            finishFailed(runId, last, msg, log.path.takeIf { log.flush() }, task.direction, runStartMs, task.remoteName, metered)
            runCatching {
                NotificationManagerCompat.from(applicationContext)
                    .notify(SyncNotifications.resultId(taskId), notifications.error(task.name, msg, taskId))
            }
            // Auth failures are non-retryable and mark the remote so the UI can prompt re-auth.
            // Recognise both the typed VirgaError.Auth and message-shaped auth errors.
            if (failure is VirgaError.Auth || isAuthError(msg)) {
                runCatching { remoteRepository.setNeedsReauth(task.remoteName, true) }
                    .onFailure { Log.w(TAG, "Failed to set needsReauth flag for ${task.remoteName}", it) }
            }
            retryDecision(failure, runAttemptCount, task)
        }

        // LAST statement: a calendar schedule runs as a one-shot, so queue the NEXT
        // occurrence now that this run is fully finalized (regardless of outcome).
        // Done after the result is determined and history is written so the
        // re-enqueue (APPEND_OR_REPLACE) can't race this still-RUNNING worker and
        // cancel its history/notification. Interval/periodic tasks repeat on their
        // own and don't need re-enqueuing here.
        reenqueueCalendar(task, isManualRun)
        return result
    }

    /**
     * Arm the NEXT occurrence of a calendar-scheduled [task]. A calendar schedule
     * runs as a one-shot, so each run must queue the next one or the schedule dies.
     * No-op for interval/periodic tasks (they repeat on their own), disabled
     * tasks, and manual "_now" runs ([isManualRun]) — only the scheduled worker
     * owns the calendar cadence, so re-arming from a manual run would double-arm
     * it. Called both on the normal completion path and the [anotherRunInFlight]
     * skip path so bailing never drops the schedule.
     */
    private suspend fun reenqueueCalendar(task: SyncTask, isManualRun: Boolean) {
        if (isManualRun) return
        if (task.scheduleDaysMask == 0 || !task.enabled) return
        // A swallowed failure here means the calendar task silently never runs
        // again — log it so the drop is at least diagnosable.
        runCatching { scheduler.schedule(task) }
            .onFailure { Log.w(TAG, "Failed to re-enqueue calendar schedule for task ${task.id}", it) }
    }

    /**
     * Decides whether to retry or fail based on the error type, attempt count, and
     * per-task retry config. Auth failures are never retried (caller must handle
     * needsReauth before calling this). runAttemptCount is 0-based: attempt 0 is the
     * first try. With maxRetries=3: attempts 0 and 1 retry (runAttemptCount < 2),
     * attempt 2 fails — yielding exactly 3 total tries.
     */
    private fun retryDecision(failure: Throwable, attempt: Int, task: SyncTask): Result =
        when (retryDecisionFor(failure, attempt, task)) {
            RetryOutcome.RETRY -> Result.retry()
            RetryOutcome.FAIL -> Result.failure()
        }

    /**
     * Advisory pre-sync conflict detection for one-way tasks. Runs a check (no transfer)
     * and records differing files as a single advisory conflict row so the user sees them
     * in ConflictsScreen. DETECTION-ONLY: does NOT block or alter the sync. No-op when
     * [task.conflictCheck][SyncTask.conflictCheck] is false, direction is BISYNC, or the
     * source is a SAF URI (check is unavailable for SAF). Daemon must already be leased.
     */
    private suspend fun runOneWayConflictCheck(task: SyncTask, effectiveTask: SyncTask) {
        if (!task.conflictCheck) return
        if (task.direction == SyncDirection.BISYNC) return
        if (!checkUseCase.isAvailableFor(task)) return
        val result = runCatching {
            var differences = 0
            executor.runCheck(effectiveTask)
                .catch { Log.w(TAG, "pre-sync check flow error", it) }
                .collect { differences = it.errors }
            differences
        }
        val diffs = result.getOrNull() ?: return
        if (diffs > 0) {
            runCatching { conflictRepository.recordOneWayAdvisory(task, diffs) }
                .onFailure { Log.w(TAG, "Failed to record one-way advisory conflicts", it) }
        }
    }

    /**
     * Best-effort per-file failure capture for a partial-success run, scoped to the
     * run's [statsGroup]. Returns the failures as newline-joined "path\terror" lines
     * (tab/newline sanitised), or "" if there are none — or on any non-cancellation
     * error: a stats-fetch failure must never turn a completed run into a crash.
     * [kotlinx.coroutines.CancellationException] is rethrown so structured concurrency
     * (worker cancellation) is preserved — a plain `runCatching` would swallow it.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun captureFailedFiles(statsGroup: String, log: RunLogWriter): String {
        val transfers = try {
            engine.transferredFiles(statsGroup)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            return ""
        }
        val failures = transfers.filter { it.error.isNotBlank() }
        if (failures.isEmpty()) return ""
        val capped = if (failures.size > FAILED_FILES_CAP) {
            log.line("Per-file failure list truncated to $FAILED_FILES_CAP entries (${failures.size} total).")
            failures.take(FAILED_FILES_CAP)
        } else {
            failures
        }
        log.line("Failed files (${capped.size}):")
        capped.forEach { log.line("  ${it.name}: ${it.error}") }
        // Sanitise the tab field-separator and newline row-separator out of both fields:
        // rclone errors are often multi-line; a raw newline would split one entry across
        // rows (the continuation has no tab and is dropped on parse). One entry per line.
        return capped.joinToString("\n") { "${it.name.sanitiseRow()}\t${it.error.sanitiseRow()}" }
    }

    /** Record a SUCCESS run from the last observed [progress] snapshot. */
    private suspend fun finishSucceeded(
        runId: Long,
        progress: SyncProgress?,
        logPath: String? = null,
        direction: SyncDirection,
        runStartMs: Long,
        failedFiles: String = "",
        remoteName: String = "",
        metered: Boolean = false,
    ) {
        historyRepository.finishRun(
            runId = runId,
            status = SyncStatus.SUCCESS,
            filesTransferred = progress?.transferredFiles ?: 0,
            bytesTransferred = progress?.bytesTransferred ?: 0L,
            errorCount = progress?.errors ?: 0,
            logPath = logPath,
            failedFiles = failedFiles,
            remoteName = remoteName,
            direction = direction.name,
            startedAtEpochMs = runStartMs,
            metered = metered,
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
        remoteName: String = "",
        metered: Boolean = false,
    ) {
        historyRepository.finishRun(
            runId = runId,
            status = SyncStatus.FAILED,
            filesTransferred = progress?.transferredFiles ?: 0,
            bytesTransferred = progress?.bytesTransferred ?: 0L,
            errorCount = (progress?.errors ?: 0) + 1,
            errorMessage = message,
            logPath = logPath,
            remoteName = remoteName,
            direction = direction.name,
            startedAtEpochMs = runStartMs,
            metered = metered,
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
        remoteName: String = "",
        metered: Boolean = false,
    ) {
        historyRepository.finishRun(
            runId = runId,
            status = SyncStatus.CANCELLED,
            filesTransferred = progress?.transferredFiles ?: 0,
            bytesTransferred = progress?.bytesTransferred ?: 0L,
            errorCount = progress?.errors ?: 0,
            remoteName = remoteName,
            direction = direction.name,
            startedAtEpochMs = runStartMs,
            metered = metered,
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
    private suspend fun startForegroundQuietly(taskId: Long, notification: android.app.Notification) {
        try {
            setForeground(foregroundInfo(taskId, notification))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException is API 31+; catch broadly so
            // older API levels (where the class isn't loaded) still compile and run.
            Log.w(TAG, "Could not start foreground service; continuing without it", e)
        }
    }

    /**
     * Returns true when the current time falls inside the global quiet-hours window
     * and quiet hours are enabled. Reads the prefs snapshot synchronously; any
     * failure (corrupt prefs, cancelled coroutine) defaults to false so the sync
     * runs rather than being incorrectly suppressed.
     */
    private suspend fun isWithinQuietHours(): Boolean {
        val prefs = runCatching { preferencesRepository.preferences.first() }
            // Rethrow cancellation so a cancelled worker stops instead of slipping
            // past the gate into a sync (structured concurrency — see captureFailedFiles).
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull() ?: return false
        if (!prefs.quietHoursEnabled) return false
        val now = java.util.Calendar.getInstance()
        val minuteOfDay = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return SyncSchedule.isWithinBlackout(
            minuteOfDay, prefs.quietHoursStartMinutes, prefs.quietHoursEndMinutes,
        )
    }

    /**
     * True when the monthly metered usage meets or exceeds the configured cap.
     * Returns false when the cap is disabled, not configured, or prefs are unreadable.
     * CancellationException is rethrown so structured concurrency is preserved.
     */
    private suspend fun isMeteredCapReached(): Boolean {
        val prefs = runCatching { preferencesRepository.preferences.first() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull() ?: return false
        if (!prefs.meteredCapEnabled || prefs.meteredCapMb <= 0) return false
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val monthStart = cal.timeInMillis
        val usedBytes = runCatching { historyRepository.monthlyMeteredBytes(monthStart).first() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrElse { 0L }
        return usedBytes >= prefs.meteredCapMb * 1024 * 1024
    }

    /**
     * True if a sibling unique-work name for [taskId] is RUNNING in a worker other
     * than this one. Guards against the scheduled and manual ("_now") runs of the
     * same task executing concurrently (shared staging dir, mirror data loss).
     */
    internal open fun anotherRunInFlight(taskId: Long): Boolean {
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

    private fun foregroundInfo(taskId: Long, notification: android.app.Notification): ForegroundInfo {
        // Per-task foreground id so concurrent "Sync all" runs don't share (and
        // clobber) one progress notification + Cancel action.
        val id = SyncNotifications.foregroundId(taskId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        // Set by SyncScheduler.syncNow: marks the manual "_now" run so it never
        // re-arms the calendar (only the scheduled worker owns that cadence).
        const val KEY_MANUAL = "manual"
        const val UNIQUE_PREFIX = "sync_task_"
        private const val TAG = "SyncWorker"
        // Retain ~30 days of sync history; older runs are pruned each invocation.
        private const val RUN_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        // Maximum number of per-file failure entries stored per run. Bounded to prevent
        // a pathological run (many thousands of errors) from filling internal storage.
        internal const val FAILED_FILES_CAP = 100
    }
}

/** Collapses tab/newline to spaces so a value can't break the "path\terror"-per-line
 *  encoding of [SyncRunEntity.failedFiles]. */
private fun String.sanitiseRow(): String = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

/** Appends [stalledFile] to the newline-joined `path\terror` [failedFiles] record, unless
 *  null or already listed. Used so a soft-stalled file (which rclone never reports as an
 *  error — the read never returned) still shows up in the run's failed-files list. */
internal fun mergeStalledFile(failedFiles: String, stalledFile: String?): String {
    if (stalledFile.isNullOrBlank()) return failedFiles
    val alreadyListed = failedFiles.lineSequence().any { it.substringBefore('\t') == stalledFile }
    if (alreadyListed) return failedFiles
    val row = "$stalledFile\tstalled: read timed out"
    return if (failedFiles.isBlank()) row else "$failedFiles\n$row"
}

/**
 * User-facing copy for a [VirgaError.Stall]. An upload that wedged reading a local/staged
 * source is almost always failing storage (e.g. an SD card going read-only), so we say so
 * and name the file; a download/remote-side stall points at the connection instead.
 */
internal fun stallUserMessage(
    error: VirgaError.Stall,
    direction: SyncDirection,
    sourceIsLocal: Boolean,
): String {
    val file = error.file
    val fileSuffix = if (file != null) " (last read: $file)" else ""
    return if (direction != SyncDirection.DOWNLOAD && sourceIsLocal) {
        "Couldn't read your source$fileSuffix — the card or drive may be failing. " +
            "Copy your files off and replace it."
    } else {
        "The transfer stalled$fileSuffix — check your connection and try again."
    }
}

internal enum class RetryOutcome { RETRY, FAIL }

/** Pure retry policy. A [VirgaError.Stall] is never retried — re-running hammers the
 *  same unreadable region. Network errors (and rclone errors when the task opts in)
 *  retry within the attempt budget. Auth is handled by the caller before this. */
internal fun retryDecisionFor(failure: Throwable, attempt: Int, task: SyncTask): RetryOutcome {
    if (failure is VirgaError.Stall) return RetryOutcome.FAIL
    val isAuth = failure is VirgaError.Auth || isAuthError(failure.message ?: "")
    if (isAuth) return RetryOutcome.FAIL
    val retryable = failure is VirgaError.Network ||
        (task.retryOnRclone && failure is VirgaError.Rclone)
    return if (retryable && attempt < task.maxRetries - 1) RetryOutcome.RETRY else RetryOutcome.FAIL
}

/** A run-log warning when [readTimeouts] source files were abandoned because their read
 *  exceeded the per-file timeout — a strong signal the source storage is failing. Null
 *  when nothing timed out. */
internal fun stagingTimeoutWarning(readTimeouts: Int): String? =
    if (readTimeouts > 0) {
        "$readTimeouts file(s) timed out while reading the source — the card or drive may " +
            "be failing. Copy your files off and replace it."
    } else {
        null
    }
