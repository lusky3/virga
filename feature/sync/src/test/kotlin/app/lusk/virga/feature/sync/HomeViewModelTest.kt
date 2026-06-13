package app.lusk.virga.feature.sync

import app.lusk.virga.core.common.model.LifetimeStats
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.StatsRepository
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.sync.SyncScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val tasksFlow = MutableStateFlow<List<SyncTask>>(emptyList())
    private val runsFlow = MutableStateFlow<List<SyncRun>>(emptyList())
    private val statsFlow = MutableStateFlow(LifetimeStats())
    private val remotesFlow = MutableStateFlow<List<Remote>>(emptyList())

    private val taskRepository: SyncTaskRepository = mockk(relaxed = true) {
        every { tasks } returns tasksFlow
    }
    private val historyRepository: SyncHistoryRepository = mockk(relaxed = true) {
        every { recentRuns } returns runsFlow
    }
    private val statsRepository: StatsRepository = mockk(relaxed = true) {
        every { stats } returns statsFlow
    }
    private val remoteRepository: RemoteRepository = mockk(relaxed = true) {
        every { remotes } returns remotesFlow
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)

    private fun viewModel() =
        HomeViewModel(taskRepository, historyRepository, statsRepository, remoteRepository, scheduler)

    // --- initial state -----------------------------------------------------

    @Test
    fun uiState_initialValue_isEmptyIdle() {
        val vm = viewModel()

        val state = vm.uiState.value
        assertThat(state.homeStatus).isEqualTo(HomeStatus.Idle)
        assertThat(state.lifetimeBytes).isEqualTo(0L)
        assertThat(state.lifetimeRuns).isEqualTo(0L)
        assertThat(state.taskCount).isEqualTo(0)
        assertThat(state.remoteCount).isEqualTo(0)
        assertThat(state.hasEnabledTasks).isFalse()
    }

    // --- aggregation across the four repositories --------------------------

    @Test
    fun uiState_combinesCountsStatsAndStatus() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        tasksFlow.value = listOf(task(id = 1L, enabled = true), task(id = 2L, enabled = false))
        runsFlow.value = listOf(run(taskId = 1L, status = SyncStatus.SUCCESS, endedAt = 5_000L))
        statsFlow.value = LifetimeStats(totalBytesTransferred = 4_096L, totalRuns = 12L)
        remotesFlow.value = listOf(Remote("gdrive", "drive"), Remote("box", "box"))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.taskCount).isEqualTo(2)
        assertThat(state.remoteCount).isEqualTo(2)
        assertThat(state.lifetimeBytes).isEqualTo(4_096L)
        assertThat(state.lifetimeRuns).isEqualTo(12L)
        assertThat(state.hasEnabledTasks).isTrue()
        // task 2 has no run; task 1 succeeded -> UpToDate with that run's endedAt.
        assertThat(state.homeStatus).isEqualTo(HomeStatus.UpToDate(5_000L))
        job.cancel()
    }

    @Test
    fun uiState_hasEnabledTasks_isFalseWhenAllTasksDisabled() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        tasksFlow.value = listOf(task(id = 1L, enabled = false), task(id = 2L, enabled = false))
        advanceUntilIdle()

        assertThat(vm.uiState.value.hasEnabledTasks).isFalse()
        assertThat(vm.uiState.value.taskCount).isEqualTo(2)
        job.cancel()
    }

    // --- status derivation: running wins over a failure --------------------

    @Test
    fun uiState_status_isRunningWhenAnyLatestRunIsRunning() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        tasksFlow.value = listOf(task(id = 1L), task(id = 2L))
        runsFlow.value = listOf(
            run(taskId = 1L, status = SyncStatus.RUNNING),
            run(taskId = 2L, status = SyncStatus.FAILED),
        )
        advanceUntilIdle()

        assertThat(vm.uiState.value.homeStatus).isEqualTo(HomeStatus.Running)
        job.cancel()
    }

    @Test
    fun uiState_status_isNeedsAttentionCountingFailedTasks() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        tasksFlow.value = listOf(task(id = 1L), task(id = 2L), task(id = 3L))
        runsFlow.value = listOf(
            run(taskId = 1L, status = SyncStatus.FAILED),
            run(taskId = 2L, status = SyncStatus.FAILED),
            run(taskId = 3L, status = SyncStatus.SUCCESS),
        )
        advanceUntilIdle()

        assertThat(vm.uiState.value.homeStatus).isEqualTo(HomeStatus.NeedsAttention(2))
        job.cancel()
    }

    // --- latest run per task: newest startedAt wins ------------------------

    @Test
    fun uiState_status_usesMostRecentRunPerTask() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        tasksFlow.value = listOf(task(id = 1L))
        // An older failed run plus a newer successful run for the same task.
        runsFlow.value = listOf(
            run(taskId = 1L, status = SyncStatus.FAILED, startedAt = 1_000L, endedAt = 1_500L),
            run(taskId = 1L, status = SyncStatus.SUCCESS, startedAt = 9_000L, endedAt = 9_900L),
        )
        advanceUntilIdle()

        // The newer SUCCESS is the latest run, so the dashboard is all-clear.
        assertThat(vm.uiState.value.homeStatus).isEqualTo(HomeStatus.UpToDate(9_900L))
        job.cancel()
    }

    // --- backUpNow ---------------------------------------------------------

    @Test
    fun backUpNow_triggersSyncForEnabledTasksOnly() = runTest(mainDispatcher.dispatcher) {
        tasksFlow.value = listOf(
            task(id = 1L, enabled = true),
            task(id = 2L, enabled = false),
            task(id = 3L, enabled = true),
        )
        val vm = viewModel()

        vm.backUpNow()
        advanceUntilIdle()

        verify(exactly = 1) { scheduler.syncNow(1L) }
        verify(exactly = 1) { scheduler.syncNow(3L) }
        verify(exactly = 0) { scheduler.syncNow(2L) }
    }

    @Test
    fun backUpNow_doesNothingWhenNoEnabledTasks() = runTest(mainDispatcher.dispatcher) {
        tasksFlow.value = listOf(task(id = 1L, enabled = false))
        val vm = viewModel()

        vm.backUpNow()
        advanceUntilIdle()

        verify(exactly = 0) { scheduler.syncNow(any()) }
    }

    // --- helpers -----------------------------------------------------------

    private fun task(id: Long, enabled: Boolean = true) = SyncTask(
        id = id,
        name = "task-$id",
        sourcePath = "/src",
        remoteName = "gdrive",
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
        enabled = enabled,
    )

    private fun run(
        taskId: Long,
        status: SyncStatus,
        startedAt: Long = 1_000L,
        endedAt: Long? = null,
    ) = SyncRun(
        id = 0,
        taskId = taskId,
        startedAtEpochMs = startedAt,
        endedAtEpochMs = endedAt,
        status = status,
    )
}
