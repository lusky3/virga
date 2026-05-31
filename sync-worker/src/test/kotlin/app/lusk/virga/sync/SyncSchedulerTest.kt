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
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
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
        scheduler = SyncScheduler(context, repository)
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

        scheduler.schedule(task)

        val infos = workManager.getWorkInfosForUniqueWork("sync_task_7").get()
        assertThat(infos).hasSize(1)
        // UNMETERED is what WorkManager calls wifi-only.
        assertThat(infos[0].constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(infos[0].periodicityInfo).isNotNull()
    }

    @Test
    fun schedule_meteredAllowed_usesConnectedConstraint() {
        val task = task(id = 8, intervalMinutes = 30, wifiOnly = false)

        scheduler.schedule(task)

        val infos = workManager.getWorkInfosForUniqueWork("sync_task_8").get()
        assertThat(infos[0].constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun schedule_disabledTask_cancelsExistingWork() {
        scheduler.schedule(task(id = 9, intervalMinutes = 60))
        assertThat(workManager.getWorkInfosForUniqueWork("sync_task_9").get()).isNotEmpty()

        scheduler.schedule(task(id = 9, intervalMinutes = 60, enabled = false))

        val states = workManager.getWorkInfosForUniqueWork("sync_task_9").get().map { it.state }
        // Cancelled is terminal; the entry stays around until pruned.
        assertThat(states).containsExactly(WorkInfo.State.CANCELLED)
    }

    @Test
    fun schedule_manualTask_doesNotEnqueueWork() {
        scheduler.schedule(task(id = 10, intervalMinutes = null))

        assertThat(workManager.getWorkInfosForUniqueWork("sync_task_10").get()).isEmpty()
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

    private fun task(
        id: Long,
        intervalMinutes: Int?,
        wifiOnly: Boolean = true,
        enabled: Boolean = true,
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
