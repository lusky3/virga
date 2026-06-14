package app.lusk.virga.sync

import android.content.Context
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
import app.lusk.virga.core.data.StatsRepository
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.rclone.RcloneEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
    }

    // inFlightOverride: when non-null, the built worker's anotherRunInFlight() seam
    // returns it (true = simulate a sibling run already in flight) without needing a
    // live WorkManager. null = production WorkManager-backed behaviour.
    private fun buildWorker(inFlightOverride: Boolean? = null, manual: Boolean = false): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(
                if (manual) {
                    workDataOf(SyncWorker.KEY_TASK_ID to TASK_ID, SyncWorker.KEY_MANUAL to true)
                } else {
                    workDataOf(SyncWorker.KEY_TASK_ID to TASK_ID)
                },
            )
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
                        )
                    } else {
                        object : SyncWorker(
                            appContext, workerParameters, executor, engine, taskRepository,
                            historyRepository, conflictRepository, statsRepository, staging, scheduler,
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
        coVerify { historyRepository.finishRun(RUN_ID, SyncStatus.SUCCESS, any(), any(), any(), any(), any()) }
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
        coVerify { historyRepository.finishRun(RUN_ID, SyncStatus.FAILED, any(), any(), any(), any(), any()) }
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
            historyRepository.finishRun(RUN_ID, SyncStatus.SUCCESS, 5, any(), 2, any(), any())
        }
        coVerify(exactly = 0) {
            historyRepository.finishRun(RUN_ID, SyncStatus.FAILED, any(), any(), any(), any(), any())
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
    fun networkFailure_returnsRetry() = runBlocking {
        val task = task(direction = SyncDirection.UPLOAD)
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            flow { throw VirgaError.Network("connection reset") }

        val result = buildWorker().doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        coVerify { historyRepository.finishRun(RUN_ID, SyncStatus.FAILED, any(), any(), any(), any(), any()) }
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
        coVerify { historyRepository.finishRun(RUN_ID, SyncStatus.CANCELLED, any(), any(), any(), any(), any()) }
    }

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
    )

    private companion object {
        const val TASK_ID = 1L
        const val RUN_ID = 99L
    }
}
