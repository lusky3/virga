package app.lusk.virga.feature.sync

import androidx.paging.PagingData
import app.lusk.virga.core.common.model.NamedSyncRun
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.sync.SyncScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class SyncHistoryViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val runsFlow = MutableStateFlow<List<SyncRun>>(emptyList())
    private val tasksFlow = MutableStateFlow<List<SyncTask>>(emptyList())
    private val historyRepository: SyncHistoryRepository = mockk(relaxed = true) {
        every { recentRuns } returns runsFlow
        every { pagedRuns(any(), any(), any()) } returns flowOf(PagingData.empty<NamedSyncRun>())
    }
    private val taskRepository: SyncTaskRepository = mockk(relaxed = true) {
        every { tasks } returns tasksFlow
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)

    private fun viewModel() = SyncHistoryViewModel(historyRepository, taskRepository, scheduler)

    // --- pre-existing tests -------------------------------------------------

    @Test
    fun isTerminal_returnsTrueForSuccessFailedCancelled_falseOtherwise() {
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.SUCCESS)).isTrue()
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.FAILED)).isTrue()
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.CANCELLED)).isTrue()
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.RUNNING)).isFalse()
    }

    @Test
    fun uiState_joinsRunsWithTaskNames() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(
            task(id = 1, name = "Photos"),
            task(id = 2, name = "Music"),
        )
        runsFlow.value = listOf(run(id = 10, taskId = 1), run(id = 11, taskId = 2))
        advanceUntilIdle()

        val rows = vm.uiState.value.rows
        assertThat(rows.map { it.taskName }).containsExactly("Photos", "Music").inOrder()
        assertThat(vm.uiState.value.loading).isFalse()
        job.cancel()
    }

    @Test
    fun uiState_fallsBackToDeletedTaskLabel_whenTaskMissing() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        runsFlow.value = listOf(run(id = 10, taskId = 99))
        tasksFlow.value = emptyList()
        advanceUntilIdle()

        assertThat(vm.uiState.value.rows.single().taskName).isEqualTo("(deleted task)")
        job.cancel()
    }

    // --- combine: rows include task name and run data -----------------------

    @Test
    fun uiState_rowsContainTheActualRunEntity() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "T"))
        val theRun = run(id = 42, taskId = 1, status = SyncStatus.FAILED)
        runsFlow.value = listOf(theRun)
        advanceUntilIdle()

        assertThat(vm.uiState.value.rows.single().run).isEqualTo(theRun)
        job.cancel()
    }

    // --- HistoryTaskFilter list only for tasks present in history -----------

    @Test
    fun uiState_taskFilterList_onlyIncludesTasksPresentInHistory() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "Photos"), task(id = 2, name = "Videos"))
        runsFlow.value = listOf(run(id = 10, taskId = 1))
        advanceUntilIdle()

        val filterIds = vm.uiState.value.tasks.map { it.id }
        assertThat(filterIds).containsExactly(1L)
        job.cancel()
    }

    @Test
    fun uiState_taskFilterList_isEmptyWhenNoRunsExist() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "Photos"))
        runsFlow.value = emptyList()
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks).isEmpty()
        job.cancel()
    }

    // --- setFilter ----------------------------------------------------------

    @Test
    fun setFilter_nullShowsAllRuns() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "T1"), task(id = 2, name = "T2"))
        runsFlow.value = listOf(run(id = 10, taskId = 1), run(id = 11, taskId = 2))
        advanceUntilIdle()

        vm.setFilter(null)
        advanceUntilIdle()

        assertThat(vm.uiState.value.rows).hasSize(2)
        job.cancel()
    }

    @Test
    fun setFilter_taskId_showsOnlyRunsForThatTask() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "T1"), task(id = 2, name = "T2"))
        runsFlow.value = listOf(
            run(id = 10, taskId = 1),
            run(id = 11, taskId = 2),
            run(id = 12, taskId = 1),
        )
        advanceUntilIdle()

        vm.setFilter(1L)
        advanceUntilIdle()

        assertThat(vm.uiState.value.rows.map { it.run.id }).containsExactly(10L, 12L)
        assertThat(vm.uiState.value.selectedTaskId).isEqualTo(1L)
        job.cancel()
    }

    @Test
    fun setStatusFilter_filtersRowsByStatus() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "T"))
        runsFlow.value = listOf(
            run(id = 10, taskId = 1, status = SyncStatus.SUCCESS),
            run(id = 11, taskId = 1, status = SyncStatus.FAILED),
        )
        advanceUntilIdle()

        vm.setStatusFilter(SyncStatus.FAILED)
        advanceUntilIdle()

        assertThat(vm.uiState.value.rows.map { it.run.id }).containsExactly(11L)
        job.cancel()
    }

    @Test
    fun setStatusFilter_null_showsAllStatuses() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "T"))
        runsFlow.value = listOf(
            run(id = 10, taskId = 1, status = SyncStatus.SUCCESS),
            run(id = 11, taskId = 1, status = SyncStatus.FAILED),
        )
        advanceUntilIdle()

        vm.setStatusFilter(SyncStatus.FAILED)
        advanceUntilIdle()
        vm.setStatusFilter(null)
        advanceUntilIdle()

        assertThat(vm.uiState.value.rows).hasSize(2)
        job.cancel()
    }

    // --- retryRun -----------------------------------------------------------

    @Test
    fun retryRun_callsSchedulerSyncNowWithTaskId() {
        val theRun = run(id = 20, taskId = 7L)

        viewModel().retryRun(theRun)

        verify(exactly = 1) { scheduler.syncNow(7L) }
    }

    // --- clearHistory -------------------------------------------------------

    @Test
    fun clearHistory_callsRepositoryClearAll() = runTest(mainDispatcher.dispatcher) {
        viewModel().clearHistory()
        advanceUntilIdle()

        coVerify(exactly = 1) { historyRepository.clearAll() }
    }

    // --- isTerminal completeness -------------------------------------------

    @Test
    fun isTerminal_isFalseForQueuedAndIdle() {
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.QUEUED)).isFalse()
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.IDLE)).isFalse()
    }

    // --- setSearchQuery -------------------------------------------------------

    @Test
    fun setSearchQuery_updatesSearchQueryInUiState() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "Photos"))
        runsFlow.value = listOf(run(id = 10, taskId = 1))
        advanceUntilIdle()

        vm.setSearchQuery("hello")
        advanceUntilIdle()

        assertThat(vm.uiState.value.searchQuery).isEqualTo("hello")
        job.cancel()
    }

    @Test
    fun setSearchQuery_emptyQueryShowsAllRows() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "Photos"))
        runsFlow.value = listOf(run(id = 10, taskId = 1), run(id = 11, taskId = 1))
        advanceUntilIdle()

        vm.setSearchQuery("")
        advanceUntilIdle()

        assertThat(vm.uiState.value.rows).hasSize(2)
        job.cancel()
    }

    // --- helpers ------------------------------------------------------------

    private fun task(id: Long, name: String) = SyncTask(
        id = id,
        name = name,
        sourcePath = "/src",
        remoteName = "gdrive",
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
    )

    private fun run(
        id: Long,
        taskId: Long,
        status: SyncStatus = SyncStatus.SUCCESS,
    ) = SyncRun(
        id = id,
        taskId = taskId,
        startedAtEpochMs = 1_000L,
        status = status,
    )
}
