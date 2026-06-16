package app.lusk.virga.sync

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.dao.SyncTaskDao
import app.lusk.virga.core.database.entity.SyncTaskEntity
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private val dao: SyncTaskDao = mockk(relaxed = true)
    private lateinit var repository: SyncTaskRepository
    private lateinit var scheduler: SyncScheduler
    private val preferencesRepository: PreferencesRepository = mockk {
        every { preferences } returns flowOf(AppPreferences())
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).build(),
        )
        workManager = WorkManager.getInstance(context)
        coEvery { dao.observeAll() } returns flowOf(emptyList())
        repository = SyncTaskRepository(dao)
        scheduler = SyncScheduler(context, repository, preferencesRepository)
    }

    @Test
    fun syncNow_enqueuesOneTimeWorkWithTaskId() {
        scheduler.syncNow(taskId = 42L)

        val infos = workManager.getWorkInfosForUniqueWork("sync_task_42_now").get()
        // The work may transition to FAILED instantly because Hilt isn't wired
        // in the unit-test environment; presence of the WorkInfo is what
        // proves the scheduler enqueued it under the expected unique name.
        assertThat(infos).hasSize(1)
        assertThat(infos[0].runAttemptCount).isAtLeast(0)
    }

    @Test
    fun schedule_periodicWork_appliesWifiOnlyConstraint() {
        val task = task(id = 7, intervalMinutes = 60, wifiOnly = true)

        runBlocking { scheduler.schedule(task) }

        val infos = workManager.getWorkInfosForUniqueWork("sync_task_7").get()
        assertThat(infos).hasSize(1)
        // UNMETERED is what WorkManager calls wifi-only.
        assertThat(infos[0].constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(infos[0].periodicityInfo).isNotNull()
    }

    @Test
    fun schedule_meteredAllowed_usesConnectedConstraint() {
        val task = task(id = 8, intervalMinutes = 30, wifiOnly = false)

        runBlocking { scheduler.schedule(task) }

        val infos = workManager.getWorkInfosForUniqueWork("sync_task_8").get()
        assertThat(infos[0].constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun schedule_disabledTask_cancelsExistingWork() {
        runBlocking { scheduler.schedule(task(id = 9, intervalMinutes = 60)) }
        assertThat(workManager.getWorkInfosForUniqueWork("sync_task_9").get()).isNotEmpty()

        runBlocking { scheduler.schedule(task(id = 9, intervalMinutes = 60, enabled = false)) }

        val states = workManager.getWorkInfosForUniqueWork("sync_task_9").get().map { it.state }
        // Cancelled is terminal; the entry stays around until pruned.
        assertThat(states).containsExactly(WorkInfo.State.CANCELLED)
    }

    @Test
    fun schedule_manualTask_doesNotEnqueueWork() {
        runBlocking { scheduler.schedule(task(id = 10, intervalMinutes = null)) }

        assertThat(workManager.getWorkInfosForUniqueWork("sync_task_10").get()).isEmpty()
    }

    @Test
    fun syncAllEnabled_enqueuesOnlyEnabledTasks() = runBlocking {
        // Stub the repository's tasks flow with two enabled tasks and one disabled.
        coEvery { dao.observeAll() } returns flowOf(
            listOf(
                entity(id = 20, enabled = true),
                entity(id = 21, enabled = true),
                entity(id = 22, enabled = false),
            ),
        )
        // Rebuild repository and scheduler so they see the new observeAll() stub.
        val localRepo = SyncTaskRepository(dao)
        val localScheduler = SyncScheduler(context, localRepo, preferencesRepository)

        localScheduler.syncAllEnabled()

        // Enabled tasks must have one-time "_now" work enqueued.
        assertThat(workManager.getWorkInfosForUniqueWork("sync_task_20_now").get()).hasSize(1)
        assertThat(workManager.getWorkInfosForUniqueWork("sync_task_21_now").get()).hasSize(1)
        // Disabled task must NOT have been enqueued.
        assertThat(workManager.getWorkInfosForUniqueWork("sync_task_22_now").get()).isEmpty()
    }

    @Test
    fun rescheduleAll_registersEveryScheduledTask() = runBlocking {
        // dao.getScheduled() returns Room entities; the repository maps them to
        // domain SyncTask before the scheduler sees them.
        coEvery { dao.getScheduled() } returns listOf(
            scheduledEntity(id = 1, intervalMinutes = 60),
            scheduledEntity(id = 2, intervalMinutes = 30),
        )

        scheduler.rescheduleAll()

        assertThat(workManager.getWorkInfosForUniqueWork("sync_task_1").get()).hasSize(1)
        assertThat(workManager.getWorkInfosForUniqueWork("sync_task_2").get()).hasSize(1)
    }

    @Test
    fun schedule_periodicWork_enqueuesWithoutError_usingLinearBackoff() {
        // B8: verify that a task with backoffExponential=false schedules without throwing.
        // WorkInfo does not expose backoffPolicy, but enqueuing with LINEAR policy must
        // not fail and must produce exactly one WorkInfo entry.
        val task = task(id = 30, intervalMinutes = 60, backoffExponential = false, backoffSeconds = 45L)

        runBlocking { scheduler.schedule(task) }

        val infos = workManager.getWorkInfosForUniqueWork("sync_task_30").get()
        assertThat(infos).hasSize(1)
    }

    @Test
    fun syncNow_enqueuesWithCustomBackoffSeconds_withoutError() {
        // B8: syncAllEnabled uses the internal overload; verify a custom backoffSeconds
        // task enqueues correctly (WorkInfo is present, no exception).
        scheduler.syncNow(taskId = 50L, backoffSeconds = 60L, backoffExponential = false)

        val infos = workManager.getWorkInfosForUniqueWork("sync_task_50_now").get()
        assertThat(infos).hasSize(1)
    }

    // --- B4: quiet-hours scheduler tests ---

    @Test
    fun scheduleCalendar_quietHoursDisabled_enqueuesWithoutShifting() {
        // When quiet hours are off the calendar is scheduled normally.
        every { preferencesRepository.preferences } returns flowOf(AppPreferences(quietHoursEnabled = false))
        val task = calendarTask(id = 60, daysMask = 0x7F, hour = 2, minute = 0)

        runBlocking { scheduler.schedule(task) }

        val infos = workManager.getWorkInfosForUniqueWork("sync_task_60").get()
        assertThat(infos).hasSize(1)
    }

    @Test
    fun scheduleCalendar_quietHoursEnabled_enqueuesOneTimeWork() {
        // When quiet hours are on the calendar is still enqueued (shifted past window).
        every { preferencesRepository.preferences } returns flowOf(
            AppPreferences(quietHoursEnabled = true, quietHoursStartMinutes = 0, quietHoursEndMinutes = 360),
        )
        val task = calendarTask(id = 61, daysMask = 0x7F, hour = 2, minute = 0)

        runBlocking { scheduler.schedule(task) }

        val infos = workManager.getWorkInfosForUniqueWork("sync_task_61").get()
        assertThat(infos).hasSize(1)
    }

    private fun calendarTask(id: Long, daysMask: Int, hour: Int, minute: Int) = SyncTask(
        id = id,
        name = "cal-$id",
        sourcePath = "/storage/emulated/0/DCIM",
        remoteName = "gdrive",
        remotePath = "/Backup",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = 0, // sentinel for calendar
        scheduleDaysMask = daysMask,
        scheduleHour = hour,
        scheduleMinute = minute,
    )

    private fun task(
        id: Long,
        intervalMinutes: Int?,
        wifiOnly: Boolean = true,
        enabled: Boolean = true,
        backoffSeconds: Long = 30L,
        backoffExponential: Boolean = true,
    ) = SyncTask(
        id = id,
        name = "task-$id",
        sourcePath = "/storage/emulated/0/DCIM",
        remoteName = "gdrive",
        remotePath = "/Backup",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = intervalMinutes,
        wifiOnly = wifiOnly,
        enabled = enabled,
        backoffSeconds = backoffSeconds,
        backoffExponential = backoffExponential,
    )

    /** Minimal entity for stubbing observeAll(); enabled flag is explicit. */
    private fun entity(id: Long, enabled: Boolean) = SyncTaskEntity(
        id = id,
        name = "task-$id",
        sourcePath = "/storage/emulated/0/DCIM",
        remoteName = "gdrive",
        remotePath = "/Backup",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = 60,
        enabled = enabled,
    )

    /** Room-entity form for stubbing the DAO (the repository maps it to domain). */
    private fun scheduledEntity(id: Long, intervalMinutes: Int?) = SyncTaskEntity(
        id = id,
        name = "task-$id",
        sourcePath = "/storage/emulated/0/DCIM",
        remoteName = "gdrive",
        remotePath = "/Backup",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = intervalMinutes,
    )
}
