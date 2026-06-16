package app.lusk.virga.sync

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.ConflictRepository
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.StatsRepository
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.rclone.RcloneEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the D5 notification-suppression gate in [SyncWorker].
 *
 * The gate logic (lines ~348-364 of SyncWorker.kt):
 *   - clean success + notifyOnFailureOnly=true  → result notification NOT posted
 *   - clean success + notifyOnFailureOnly=false  → result notification IS posted
 *   - partial success (errors>0) + notifyOnFailureOnly=true → result notification IS posted
 *     (failures are never silenced)
 *
 * Notification observation: [NotificationManagerCompat.from(ctx).notify(...)] routes
 * through the Android [NotificationManager] system service, which Robolectric shadows.
 * [Shadows.shadowOf(notificationManager).activeNotifications] returns every notification
 * currently held by the shadow, keyed by notification id. The result notification uses
 * [SyncNotifications.resultId(taskId)] = 200_000 + taskId.toInt().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerNotifyOnFailureOnlyTest {

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
    private val checkUseCase: CheckUseCase = mockk(relaxed = true)

    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        notificationManager = context.getSystemService<NotificationManager>()!!
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

    // --- helpers ---

    private fun prefsWith(notifyOnFailureOnly: Boolean) = mockk<PreferencesRepository> {
        every { preferences } returns flowOf(AppPreferences(notifyOnFailureOnly = notifyOnFailureOnly))
    }

    private fun buildWorker(prefs: PreferencesRepository): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(workDataOf(SyncWorker.KEY_TASK_ID to TASK_ID))
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = SyncWorker(
                    appContext, workerParameters, executor, engine, taskRepository,
                    historyRepository, conflictRepository, statsRepository, staging, scheduler,
                    remoteRepository, prefs, checkUseCase,
                )
            })
            .build()

    private fun uploadTask() = SyncTask(
        id = TASK_ID,
        name = "test",
        sourcePath = "/sdcard/DCIM",
        remoteName = "gdrive",
        remotePath = "/Backup",
        direction = SyncDirection.UPLOAD,
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
        statsGroup = "job/1",
    )

    private fun activeResultNotificationIds(): Set<Int> =
        Shadows.shadowOf(notificationManager).activeNotifications
            .map { it.id }
            .toSet()

    // --- gate: clean success + pref true → NO result notification ---

    @Test
    fun cleanSuccess_notifyOnFailureOnlyTrue_doesNotPostResultNotification() = runBlocking {
        val task = uploadTask()
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            kotlinx.coroutines.flow.flow { emit(progress(transferred = 3, errors = 0)) }

        val result = buildWorker(prefsWith(notifyOnFailureOnly = true)).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(SyncNotifications.resultId(TASK_ID) in activeResultNotificationIds()).isFalse()
    }

    // --- gate: clean success + pref false → result notification IS posted ---

    @Test
    fun cleanSuccess_notifyOnFailureOnlyFalse_postsResultNotification() = runBlocking {
        val task = uploadTask()
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            kotlinx.coroutines.flow.flow { emit(progress(transferred = 3, errors = 0)) }

        val result = buildWorker(prefsWith(notifyOnFailureOnly = false)).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(SyncNotifications.resultId(TASK_ID) in activeResultNotificationIds()).isTrue()
    }

    // --- gate: partial success (errors > 0) + pref true → result notification IS posted ---

    @Test
    fun partialSuccess_notifyOnFailureOnlyTrue_stillPostsResultNotification() = runBlocking {
        val task = uploadTask()
        coEvery { taskRepository.getTask(TASK_ID) } returns task
        coEvery { staging.prepare(any(), any(), any()) } returns
            LocalStaging.StagedSource(localPath = task.sourcePath, isStaged = false)
        coEvery { executor.run(any(), any(), any(), any(), any()) } returns
            kotlinx.coroutines.flow.flow { emit(progress(transferred = 5, errors = 2)) }

        val result = buildWorker(prefsWith(notifyOnFailureOnly = true)).doWork()

        // Partial success: SUCCESS result but with error files — never silenced.
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(SyncNotifications.resultId(TASK_ID) in activeResultNotificationIds()).isTrue()
    }

    private companion object {
        const val TASK_ID = 1L
        const val RUN_ID = 99L
    }
}
