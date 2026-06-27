package app.lusk.virga.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.ConflictRepository
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.sync.CheckUseCase
import app.lusk.virga.core.data.StatsRepository
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.rclone.RcloneEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * Robolectric coverage for [SyncWorker]'s orchestration (audit sync-M3). Locks in
 * the C1 write-back-before-cleanup ordering, network-failure retry, and the
 * NonCancellable daemon-release on cancellation. Collaborators are mockk fakes;
 * the worker is built with [TestListenableWorkerBuilder] so setForeground/setProgress
 * resolve without a live WorkManager service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerTest {

    private lateinit var context: Context
    private val executor: SyncExecutor = mockk()
    private val engine: RcloneEngine = mockk(relaxed = true)
    private val taskRepository: SyncTaskRepository = mockk()
    private val historyRepository: SyncHistoryRepository = mockk(relaxed = true)
    private val conflictRepository: ConflictRepository = mockk(relaxed = true)
    private val statsRepository: StatsRepository = mockk(relaxed = true)
    private val staging: LocalStaging = mockk(relaxed = true)
    private val scheduler: SyncScheduler = mockk(relaxed = true)
    private val remoteRepository: RemoteRepository = mockk(relaxed = true)
    private val preferencesRepository: PreferencesRepository = mockk {
        every { preferences } returns flowOf(AppPreferences())
    }
    private val checkUseCase: CheckUseCase = mockk(relaxed = true)
    private val sourceHealthCheck: SourceHealthCheck = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // anotherRunInFlight() queries WorkManager; back it with the in-memory test
        // initializer so getInstance() resolves (no live service in unit tests).
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            androidx.work.Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build(),
        )
        coEvery { historyRepository.startRun(any()) } returns RUN_ID
        coEvery { historyRepository.hasSucceeded(any()) } returns true
        coEvery { engine.listRemotes() } returns emptyList()
        // The sample-read preflight only runs for content:// upload/bisync sources;
        // default it to healthy so existing scenarios proceed exactly as before.
        coEvery { sourceHealthCheck.probe(any(), any(), any()) } returns
            SourceHealthCheck.HealthResult.OK
    }

    // inFlightOverride: when non-null, the built worker's anotherRunInFlight() seam
    // returns it (true = simulate a sibling run already in flight) without needing a
    // live WorkManager. null = production WorkManager-backed behaviour.
    // runAttemptCount: simulates WorkManager's retry counter (0-based, 0 = first attempt).
    private fun buildWorker(
        inFlightOverride: Boolean? = null,
        manual: Boolean = false,
        runAttemptCount: Int = 0,
    ): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(
                if (manual) {
                    workDataOf(SyncWorker.KEY_TASK_ID to TASK_ID, SyncWorker.KEY_MANUAL to true)
                } else {
                    workDataOf(SyncWorker.KEY_TASK_ID to TASK_ID)
                },
            )
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    if (inFlightOverride == null) {
                        SyncWorker(
                            appContext, workerParameters, executor, engine, taskRepository,
                            historyRepository, conflictRepository, statsRepository, staging, scheduler,
                            remoteRepository, preferencesRepository, checkUseCase, sourceHealthCheck,
                        )
                    } else {
                        object : SyncWorker(
                            appContext, workerParameters, executor, engine, taskRepository,
                            historyRepository, conflictRepository, statsRepository, staging, scheduler,
                            remoteRepository, preferencesRepository, checkUseCase, sourceHealthCheck,
                        ) {
                            override fun anotherRunInFlight(taskId: Long): Boolean = inFlightOverride
                        }
                    }
            })
            .build()

    @Test
    fun stagedDownload_writesBackBeforeCleanup_andRecordsSuccess() = runBlocking {
        val task = task(direction = SyncDirection.DOWNLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        // isStaged DOWNLOAD: writeBack must run before the staging dir is cleaned.
        val staged = LocalStaging.StagedSource(
            localPath = "/cache/saf-stage/abc-$RUN_ID",
            isStaged = true,
            treeUriString = "content://tree",
            cacheDir = java.io.File("/cache/saf-stage/abc-$RUN_ID"),
        )
        coEvery { staging.prepare(task.sourcePath, task.direction, RUN_ID) } returns staged
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 3)) }

        val result = buildWorker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // C1: write-back BEFORE cleanup, or the deleted cache dir yields an empty copy.
        coVerifyOrder {
            staging.writeBack(staged)
            staging.cleanup(staged)
        }
        coVerify { historyRepository.finishRun(RUN_ID, SyncStatus.SUCCESS, any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun stagedDownload_writeBackFailure_recordsFailedAndDoesNotSucceed() = runBlocking {
        val task = task(direction = SyncDirection.DOWNLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        val staged = LocalStaging.StagedSource(
            localPath = "/cache/saf-stage/abc-$RUN_ID",
            isStaged = true,
            treeUriString = "content://tree",
            cacheDir = java.io.File("/cache/saf-stage/abc-$RUN_ID"),
        )
        coEvery { staging.prepare(task.sourcePath, task.direction, RUN_ID) } returns staged
        coEvery { staging.writeBack(staged) } throws java.io.IOException("disk full")
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 3)) }

        val result = buildWorker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        coVerify { historyRepository.finishRun(RUN_ID, SyncStatus.FAILED, any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        // Cleanup still runs even though write-back failed.
        coVerify { staging.cleanup(staged) }
    }

    @Test
    fun bisyncSuccess_runsConflictDetectionUnderAFreshDaemonLease() = runBlocking {
        // sync-M1: detectFor() → engine.listDir() needs a daemon, but the worker's
        // own lease is released (daemon stopped) in the finally before the epilogue.
        // So the epilogue must acquire a fresh lease around detectFor and release it,
        // or it leaves an orphan unleased daemon. A non-content:// source avoids the
        // SAF-bisync early-out so the run reaches the success epilogue.
        val task = SyncTask(
            id = TASK_ID,
            name = "test",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "/Backup",
            direction = SyncDirection.BISYNC,
            intervalMinutes = null,
        )
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 1)) }

        val result = buildWorker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // The epilogue's fresh lease wraps detectFor and is released afterwards.
        coVerifyOrder {
            conflictRepository.detectFor(task)
            engine.releaseDaemon()
        }
        coVerify(atLeast = 2) { engine.acquireDaemon() }
    }

    @Test
    fun copyRunWithFileErrors_recordsSuccessWithErrorCount_notFailed() = runBlocking {
        // A one-way COPY whose terminal progress carries errors>0 is a PARTIAL SUCCESS:
        // rclone copied the rest and continued. The worker records SUCCESS with a non-zero
        // errorCount (the run is NOT FAILED) and writes a count-based summary line.
        val task = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 5, errors = 2)) }

        val result = buildWorker().doWork()

        // Recorded SUCCESS (with errorCount=2), not retried, not failed.
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify {
            historyRepository.finishRun(RUN_ID, SyncStatus.SUCCESS, 5, any(), 2, any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            historyRepository.finishRun(RUN_ID, SyncStatus.FAILED, any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun anotherRunInFlight_calendarTask_reenqueuesNextOccurrence_andSucceeds() = runBlocking {
        // Regression (sync-M, MEDIUM): the mutual-exclusion guard's early return sat
        // BEFORE the calendar re-enqueue, so a scheduled run that bailed dropped its
        // own next occurrence. Under the TOCTOU window where both the scheduled and
        // manual "_now" runs observe each other as RUNNING and BOTH skip, the calendar
        // was left un-armed and the schedule silently died. The skip path must re-arm.
        val task = SyncTask(
            id = TASK_ID,
            name = "test",
            sourcePath = "content://tree/source",
            remoteName = "gdrive",
            remotePath = "/Backup",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            scheduleDaysMask = 0b0010101, // calendar-scheduled
            enabled = true,
        )
        coEvery { taskRepository.getTask(TASK_ID) } returns task

        // anotherRunInFlight() observes a sibling already RUNNING → the worker bails.
        val result = buildWorker(inFlightOverride = true).doWork()

        // Bailed early (success), but the next calendar occurrence is still armed.
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify { scheduler.schedule(task) }
        // It bailed before starting a run: no history was recorded for this worker.
        coVerify(exactly = 0) { historyRepository.startRun(any()) }
        coVerify(exactly = 0) { executor.run(any(), any(), any(), any(), any()) }
    }

    @Test
    fun anotherRunInFlight_manualRun_doesNotReenqueueCalendar() = runBlocking {
        // A manual "_now" run must NOT re-arm the calendar: only the scheduled worker
        // owns that cadence. If both re-armed, the both-skip TOCTOU window would queue
        // TWO next occurrences (APPEND_OR_REPLACE) → duplicate back-to-back syncs.
        val task = SyncTask(
            id = TASK_ID,
            name = "test",
            sourcePath = "content://tree/source",
            remoteName = "gdrive",
            remotePath = "/Backup",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            scheduleDaysMask = 0b0010101, // calendar-scheduled
            enabled = true,
        )
        coEvery { taskRepository.getTask(TASK_ID) } returns task

        val result = buildWorker(inFlightOverride = true, manual = true).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // The scheduled worker owns re-arming; this manual run must not touch it.
        coVerify(exactly = 0) { scheduler.schedule(any()) }
    }

    @Test
    fun successfulRun_prunesOldRunLogFiles() = runBlocking {
        // 0.3.0 log-pruning: after a run finishes, the worker sweeps stale run_logs/
        // files on the SAME cutoff as the DB-row prune so internal storage stays
        // bounded. RunLogWriter.pruneOlderThan is a companion call → mockkObject it.
        mockkObject(RunLogWriter)
        try {
            val task = task(direction = SyncDirection.UPLOAD)
            coEvery { taskRepository.getTask(TASK_ID) } returns task
            coEvery { staging.prepare(any(), any(), any()) } returns
                LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
            coEvery { executor.run(any(), any(), any(), any(), any()) } returns
                flow { emit(progress(transferred = 1)) }

            val result = buildWorker().doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.success())
            // The point of the change: the log sweep shares ONE cutoff with the DB
            // prune. We can't pin the wall-clock value, but we can capture both and
            // assert they're identical.
            val dbCutoff = slot<Long>()
            val logCutoff = slot<Long>()
            coVerify { historyRepository.pruneOlderThan(capture(dbCutoff)) }
            verify { RunLogWriter.pruneOlderThan(any(), capture(logCutoff)) }
            assertThat(logCutoff.captured).isEqualTo(dbCutoff.captured)
        } finally {
            unmockkObject(RunLogWriter)
        }
    }

    @Test
    fun networkFailure_belowRetryCapReturnsRetry() = runBlocking {
        // B8(a): Network failure on attempt 0 with default maxRetries=3 -> retry.
        // (Attempt 0 < maxRetries-1=2, so retry is allowed.)
        val task = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { throw VirgaError.Network("connection reset") }

        val result = buildWorker(runAttemptCount = 0).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        coVerify { historyRepository.finishRun(RUN_ID, SyncStatus.FAILED, any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun networkFailure_atRetryCapReturnsFailed() = runBlocking {
        // B8(b): Network failure at attempt maxRetries-1=2 (third try) -> fail, no more retries.
        val task = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { throw VirgaError.Network("connection reset") }

        val result = buildWorker(runAttemptCount = 2).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun rcloneFailure_retryOnRcloneFalse_returnsFailed() = runBlocking {
        // B8(c): Rclone failure with retryOnRclone=false -> always fail.
        val task = task(direction = SyncDirection.UPLOAD).copy(retryOnRclone = false)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { throw VirgaError.Rclone(message = "directory not found") }

        val result = buildWorker(runAttemptCount = 0).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun rcloneFailure_retryOnRcloneTrue_belowCap_returnsRetry() = runBlocking {
        // B8(d): Rclone non-auth failure with retryOnRclone=true and below cap -> retry.
        val task = task(direction = SyncDirection.UPLOAD).copy(retryOnRclone = true)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { throw VirgaError.Rclone(message = "io timeout") }

        val result = buildWorker(runAttemptCount = 0).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun authFailure_retryOnRcloneTrue_neverRetries() = runBlocking {
        // B8(e): Auth failure with retryOnRclone=true and retries remaining -> still fail.
        val task = task(direction = SyncDirection.UPLOAD).copy(retryOnRclone = true)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { throw VirgaError.Rclone(message = "oauth2: cannot fetch token: 401 Unauthorized") }

        val result = buildWorker(runAttemptCount = 0).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        coVerify { remoteRepository.setNeedsReauth("gdrive", true) }
    }

    @Test
    fun cancellation_releasesDaemonAndRecordsCancelled() = runBlocking {
        val task = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { engine.releaseDaemon() } just Runs
        // Cancellation strikes at a suspension point inside the try after the daemon
        // is leased (here the listRemotes() guard). It must NOT be swallowed by the
        // failure path: it propagates to the worker's CancellationException catch.
        coEvery { engine.listRemotes() } throws CancellationException("stopped")

        val worker = buildWorker()
        var thrown: Throwable? = null
        try {
            worker.doWork()
        } catch (e: CancellationException) {
            thrown = e
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        // Lease released under NonCancellable so it isn't itself cancelled.
        coVerify { engine.releaseDaemon() }
        // Run finalized CANCELLED rather than left stuck RUNNING.
        coVerify { historyRepository.finishRun(RUN_ID, SyncStatus.CANCELLED, any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun errorfulRun_capturesFailedFiles_andThreadsThemIntoFinishRun() = runBlocking {
        // B9: when errors>0 and no hard failure, the worker queries transferredFiles(),
        // filters to non-blank error entries, joins them as "path\terror" and passes
        // the result into finishRun(failedFiles=...).
        val task = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 3, errors = 1)) }
        coEvery { engine.transferredFiles("job/1") } returns listOf(
            app.lusk.virga.core.rclone.TransferredFile(name = "docs/a.txt", error = "permission denied"),
            app.lusk.virga.core.rclone.TransferredFile(name = "docs/b.txt", error = ""),
        )

        val failedFilesSlot = slot<String>()
        coEvery {
            historyRepository.finishRun(
                any(), any(), any(), any(), any(), any(), any(), capture(failedFilesSlot),
                any(), any(), any(), any(),
            )
        } just Runs

        buildWorker().doWork()

        // Only the entry with a non-blank error is captured.
        assertThat(failedFilesSlot.captured).isEqualTo("docs/a.txt\tpermission denied")
    }

    @Test
    fun errorfulRun_sanitisesTabAndNewlineInFailedFiles() = runBlocking {
        // B9: rclone errors are often multi-line. A raw newline/tab in a path or error
        // must be collapsed so each entry stays on exactly one "path\terror" line.
        val task = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 1, errors = 1)) }
        coEvery { engine.transferredFiles("job/1") } returns listOf(
            app.lusk.virga.core.rclone.TransferredFile(
                name = "docs/a.txt",
                error = "Failed to copy:\n  upstream timeout\tretry later",
            ),
        )
        val failedFilesSlot = slot<String>()
        coEvery {
            historyRepository.finishRun(any(), any(), any(), any(), any(), any(), any(), capture(failedFilesSlot), any(), any(), any(), any())
        } just Runs

        buildWorker().doWork()

        // Exactly one line; no embedded newline/tab beyond the single field separator.
        val captured = failedFilesSlot.captured
        assertThat(captured.lines()).hasSize(1)
        assertThat(captured).isEqualTo("docs/a.txt\tFailed to copy:   upstream timeout retry later")
    }

    @Test
    fun cleanRun_doesNotCallTransferredFiles_andPassesEmptyFailedFiles() = runBlocking {
        // B9: when errors==0 the worker must NOT call transferredFiles (skip the RC
        // round-trip) and must pass failedFiles="" to finishRun.
        val task = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 2, errors = 0)) }

        val failedFilesSlot = slot<String>()
        coEvery {
            historyRepository.finishRun(
                any(), any(), any(), any(), any(), any(), any(), capture(failedFilesSlot),
                any(), any(), any(), any(),
            )
        } just Runs

        buildWorker().doWork()

        coVerify(exactly = 0) { engine.transferredFiles(any()) }
        assertThat(failedFilesSlot.captured).isEmpty()
    }

    @Test
    fun deleteSource_true_threadsAllowMoveToExecutor() = runBlocking {
        val moveSlot = slot<Boolean>()
        val moveTask = SyncTask(
            id = TASK_ID,
            name = "test",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "/Backup",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            deleteSource = true,
        )
        coEvery { taskRepository.getTask(TASK_ID) } returns moveTask
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = moveTask.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), capture(moveSlot), any()) } returns
            flow { emit(progress(transferred = 1)) }

        buildWorker().doWork()

        assertThat(moveSlot.captured).isTrue()
    }

    @Test
    fun deleteSource_false_threadsAllowMoveFalseToExecutor() = runBlocking {
        val moveSlot = slot<Boolean>()
        val noMoveTask = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns noMoveTask
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = noMoveTask.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), capture(moveSlot), any()) } returns
            flow { emit(progress(transferred = 1)) }

        buildWorker().doWork()

        assertThat(moveSlot.captured).isFalse()
    }

    @Test
    fun deleteSource_true_contentSource_normalizesAllowMoveFalse() = runBlocking {
        // Execution-time invariant: a SAF (content://) source can't be moved — rclone
        // would only delete the staged copy, not the originals. A malformed persisted
        // task with deleteSource=true on a content:// source must NOT route to move.
        val moveSlot = slot<Boolean>()
        val safMoveTask = SyncTask(
            id = TASK_ID,
            name = "test",
            sourcePath = "content://tree/source",
            remoteName = "gdrive",
            remotePath = "/Backup",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            deleteSource = true,
        )
        coEvery { taskRepository.getTask(TASK_ID) } returns safMoveTask
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = safMoveTask.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), capture(moveSlot), any()) } returns
            flow { emit(progress(transferred = 1)) }

        buildWorker().doWork()

        assertThat(moveSlot.captured).isFalse()
    }

    @Test
    fun deleteSource_true_bisync_normalizesAllowMoveFalse() = runBlocking {
        // Execution-time invariant: BISYNC reconciles deletions through its own two-way
        // logic; move-mode is forbidden. A non-content:// source avoids the SAF-bisync
        // early-out so the run reaches executor.run and we can capture allowMove.
        val moveSlot = slot<Boolean>()
        val bisyncMoveTask = SyncTask(
            id = TASK_ID,
            name = "test",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "/Backup",
            direction = SyncDirection.BISYNC,
            intervalMinutes = null,
            deleteSource = true,
        )
        coEvery { taskRepository.getTask(TASK_ID) } returns bisyncMoveTask
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = bisyncMoveTask.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), capture(moveSlot), any()) } returns
            flow { emit(progress(transferred = 1)) }

        buildWorker().doWork()

        assertThat(moveSlot.captured).isFalse()
    }

    @Test
    fun authFailure_setsNeedsReauthAndRecordsFailed_doesNotRetry() = runBlocking {
        // An auth-shaped error must (a) set needsReauth for the remote, (b) record FAILED,
        // (c) NOT retry (only VirgaError.Network retries).
        val task = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { throw VirgaError.Rclone(message = "oauth2: cannot fetch token: 401 Unauthorized") }

        val result = buildWorker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        coVerify { historyRepository.finishRun(RUN_ID, SyncStatus.FAILED, any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify { remoteRepository.setNeedsReauth("gdrive", true) }
    }

    @Test
    fun nonAuthFailure_doesNotSetNeedsReauth() = runBlocking {
        val task = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { throw VirgaError.Rclone(message = "directory not found") }

        buildWorker().doWork()

        coVerify(exactly = 0) { remoteRepository.setNeedsReauth(any(), true) }
    }

    @Test
    fun successfulSync_clearsNeedsReauthFlag() = runBlocking {
        // Belt-and-suspenders: a clean run clears any lingering needsReauth flag so an
        // out-of-band token fix (e.g. rclone config on another machine) auto-recovers.
        val task = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 3)) }

        val result = buildWorker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify { remoteRepository.setNeedsReauth("gdrive", false) }
    }

    // --- B4: quiet-hours worker tests ---

    @Test
    fun scheduledRun_insideQuietWindow_returnsSuccessWithoutCallingEngine() = runBlocking {
        // A scheduled (non-manual) run during the blackout window must skip the sync
        // and return success — WorkManager must not retry it.
        // Anchor the window to the current minute so "now" is deterministically inside
        // regardless of wall-clock time (a fixed [0,1439) misses 23:59). The 2-minute
        // span absorbs a minute roll-over between this read and the worker's.
        val nowMin = nowMinuteOfDay()
        every { preferencesRepository.preferences } returns flowOf(
            AppPreferences(
                quietHoursEnabled = true,
                quietHoursStartMinutes = nowMin,
                quietHoursEndMinutes = (nowMin + 2) % 1440,
            ),
        )
        val t = calendarSyncTask()
        coEvery { taskRepository.getTask(TASK_ID) } returns t

        val result = buildWorker(manual = false).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { executor.run(any(), any(), any(), any(), any()) }
    }

    @Test
    fun manualRun_insideQuietWindow_runsNormally() = runBlocking {
        // Manual "Sync now" bypasses quiet hours regardless of the window. Anchor the
        // window to the current minute so "now" is deterministically inside it.
        val nowMin = nowMinuteOfDay()
        every { preferencesRepository.preferences } returns flowOf(
            AppPreferences(
                quietHoursEnabled = true,
                quietHoursStartMinutes = nowMin,
                quietHoursEndMinutes = (nowMin + 2) % 1440,
            ),
        )
        val t = calendarSyncTask()
        coEvery { taskRepository.getTask(TASK_ID) } returns t
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = t.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 1)) }

        val result = buildWorker(manual = true).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 1) { executor.run(any(), any(), any(), any(), any()) }
    }

    @Test
    fun scheduledRun_quietHoursDisabled_runsNormally() = runBlocking {
        // When quiet hours are disabled a scheduled run must proceed as usual.
        every { preferencesRepository.preferences } returns flowOf(
            AppPreferences(quietHoursEnabled = false),
        )
        val t = calendarSyncTask()
        coEvery { taskRepository.getTask(TASK_ID) } returns t
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = t.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 1)) }

        val result = buildWorker(manual = false).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 1) { executor.run(any(), any(), any(), any(), any()) }
    }

    // --- B7: pre-sync one-way conflict check ----------------------------------

    @Test
    fun conflictCheck_true_oneWay_nonContent_recordsAdvisoryConflict() = runBlocking {
        // When conflictCheck=true on a non-SAF one-way task, the worker runs a pre-sync
        // check and calls recordOneWayAdvisory when differences > 0.
        val t = SyncTask(
            id = TASK_ID,
            name = "test",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "/Backup",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            conflictCheck = true,
        )
        coEvery { taskRepository.getTask(TASK_ID) } returns t
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = t.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 2)) }
        every { checkUseCase.isAvailableFor(t) } returns true
        coEvery { executor.runCheck(any()) } returns
            flow { emit(progress(transferred = 0, errors = 4)) }

        buildWorker().doWork()

        coVerify { conflictRepository.recordOneWayAdvisory(t, 4) }
    }

    @Test
    fun conflictCheck_false_oneWay_doesNotRunCheck() = runBlocking {
        // Default conflictCheck=false: no pre-sync check, no advisory conflict recording.
        val t = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns t
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = t.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 1)) }

        buildWorker().doWork()

        coVerify(exactly = 0) { executor.runCheck(any()) }
        coVerify(exactly = 0) { conflictRepository.recordOneWayAdvisory(any(), any()) }
    }

    @Test
    fun conflictCheck_true_bisync_doesNotRunCheck() = runBlocking {
        // BISYNC tasks must not run the one-way check even if conflictCheck=true.
        val t = SyncTask(
            id = TASK_ID,
            name = "test",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "/Backup",
            direction = SyncDirection.BISYNC,
            intervalMinutes = null,
            conflictCheck = true,
        )
        coEvery { taskRepository.getTask(TASK_ID) } returns t
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = t.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 1)) }

        buildWorker().doWork()

        coVerify(exactly = 0) { executor.runCheck(any()) }
        coVerify(exactly = 0) { conflictRepository.recordOneWayAdvisory(any(), any()) }
    }

    // --- D4: metered data cap enforcement ---

    @Test
    fun meteredCapConfig_capDisabled_runsProceedsNormally() = runBlocking {
        // When meteredCapEnabled=false the cap is not enforced regardless of usage.
        val t = calendarSyncTask()
        coEvery { taskRepository.getTask(TASK_ID) } returns t
        every { preferencesRepository.preferences } returns flowOf(
            AppPreferences(meteredCapEnabled = false, meteredCapMb = 100L),
        )
        coEvery { historyRepository.monthlyMeteredBytes(any()) } returns flowOf(200L * 1024 * 1024)
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = t.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 1)) }

        val result = buildWorker(manual = false).doWork()

        // Cap disabled: run should proceed and succeed.
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 1) { executor.run(any(), any(), any(), any(), any()) }
    }

    @Test
    fun meteredCap_meteredNetwork_usageAtCap_skipsRunWithoutStartingHistory() = runBlocking {
        // Safety-critical positive-skip path: when the network is metered AND the monthly
        // usage meets the cap, the worker must bail before startRun (no DB row) and before
        // calling the executor (no sync). It still re-arms the calendar schedule and returns
        // Result.success() so WorkManager doesn't retry the skipped run.
        forceMeteredNetwork()

        val capMb = 100L
        val t = calendarSyncTask()
        coEvery { taskRepository.getTask(TASK_ID) } returns t
        every { preferencesRepository.preferences } returns flowOf(
            AppPreferences(meteredCapEnabled = true, meteredCapMb = capMb),
        )
        // Usage exactly at the cap boundary: usedBytes == capMb * 1024 * 1024.
        coEvery { historyRepository.monthlyMeteredBytes(any()) } returns
            flowOf(capMb * 1024L * 1024L)

        val result = buildWorker(manual = false).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // No sync_runs row was created: startRun must NOT be called.
        coVerify(exactly = 0) { historyRepository.startRun(any()) }
        // No sync ran: executor must NOT be called.
        coVerify(exactly = 0) { executor.run(any(), any(), any(), any(), any()) }
        // Calendar re-armed so the schedule isn't dropped by the skip.
        coVerify(exactly = 1) { scheduler.schedule(t) }
    }

    @Test
    fun meteredCap_meteredNetwork_usageBelowCap_runProceeds() = runBlocking {
        // Positive proceed path: metered network, cap enabled, but usage is strictly
        // below the cap — the worker must NOT skip and must call the executor normally.
        forceMeteredNetwork()

        val capMb = 100L
        val t = calendarSyncTask()
        coEvery { taskRepository.getTask(TASK_ID) } returns t
        every { preferencesRepository.preferences } returns flowOf(
            AppPreferences(meteredCapEnabled = true, meteredCapMb = capMb),
        )
        // Usage one byte below the cap: (capMb * 1024 * 1024) - 1.
        coEvery { historyRepository.monthlyMeteredBytes(any()) } returns
            flowOf(capMb * 1024L * 1024L - 1L)
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = t.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 1)) }

        val result = buildWorker(manual = false).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // Run proceeded: startRun AND executor were both called.
        coVerify(exactly = 1) { historyRepository.startRun(any()) }
        coVerify(exactly = 1) { executor.run(any(), any(), any(), any(), any()) }
    }

    @Test
    fun meteredCap_wifiNetwork_usageOverCap_runProceeds() = runBlocking {
        // Wi-Fi bypass: when the active network is NOT metered the cap is never
        // consulted even if usage is way above the configured limit. The worker
        // must NOT skip: startRun is called (a DB row is opened), and the
        // run succeeds normally.
        //
        // ShadowConnectivityManager state can persist across tests when earlier tests
        // call forceMeteredNetwork(). Explicitly reset to non-metered (null active-info)
        // so this test is not polluted by the mobile-network fixture from the prior test.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        Shadows.shadowOf(cm).setActiveNetworkInfo(null)

        val capMb = 100L
        val t = calendarSyncTask()
        coEvery { taskRepository.getTask(TASK_ID) } returns t
        every { preferencesRepository.preferences } returns flowOf(
            AppPreferences(meteredCapEnabled = true, meteredCapMb = capMb),
        )
        // Usage massively over cap: 10× the limit — cap must still be ignored on Wi-Fi.
        coEvery { historyRepository.monthlyMeteredBytes(any()) } returns
            flowOf(capMb * 10L * 1024L * 1024L)
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = t.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { emit(progress(transferred = 1)) }

        // Use inFlightOverride=false to pin the mutual-exclusion guard deterministically.
        val result = buildWorker(inFlightOverride = false, manual = false).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // startRun is the first real action after all the skip gates; its invocation
        // proves the cap gate was NOT triggered on a non-metered network.
        coVerify(exactly = 1) { historyRepository.startRun(any()) }
    }

    /**
     * Makes [ConnectivityManager.isActiveNetworkMetered] return true for the
     * current Robolectric test by installing a TYPE_MOBILE active NetworkInfo via
     * ShadowConnectivityManager. Robolectric resets shadow state between tests
     * (via @Resetter), so this does not leak across tests.
     */
    private fun forceMeteredNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mobileInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_MOBILE, // type 0 → isActiveNetworkMetered() returns true
            0,    // subType
            true, // isAvailable
            true, // isConnected (passed as Boolean overload)
        )
        Shadows.shadowOf(cm).setActiveNetworkInfo(mobileInfo)
    }

    /** Current local minute-of-day (0..1439), matching the worker's quiet-hours clock. */
    private fun nowMinuteOfDay(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    private fun calendarSyncTask() = SyncTask(
        id = TASK_ID,
        name = "test",
        sourcePath = "/sdcard/DCIM",
        remoteName = "gdrive",
        remotePath = "/Backup",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
        scheduleDaysMask = 0x7F,
        scheduleHour = 3,
        scheduleMinute = 0,
    )

    private fun task(direction: SyncDirection) = SyncTask(
        id = TASK_ID,
        name = "test",
        sourcePath = "content://tree/source",
        remoteName = "gdrive",
        remotePath = "/Backup",
        direction = direction,
        intervalMinutes = null,
    )

    private fun progress(transferred: Int, errors: Int = 0) = SyncProgress(
        bytesTransferred = 100,
        totalBytes = 100,
        speedBytesPerSec = 0.0,
        transferredFiles = transferred,
        totalFiles = transferred,
        etaSeconds = null,
        errors = errors,
        // Terminal emissions carry the run's stats group (job/<id>); the worker needs
        // it to scope the per-file failure query.
        statsGroup = "job/1",
    )

    private companion object {
        const val TASK_ID = 1L
        const val RUN_ID = 99L
    }
}
