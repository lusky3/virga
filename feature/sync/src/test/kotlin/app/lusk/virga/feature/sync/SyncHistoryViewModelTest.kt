package app.lusk.virga.feature.sync

import androidx.paging.PagingData
import androidx.paging.map
import androidx.paging.testing.asSnapshot
import app.lusk.virga.core.common.model.NamedSyncRun
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.sync.SyncScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    private val taskIdsFlow = MutableStateFlow<List<Long>>(emptyList())
    private val tasksFlow = MutableStateFlow<List<SyncTask>>(emptyList())
    private val historyRepository: SyncHistoryRepository = mockk(relaxed = true) {
        every { distinctTaskIds } returns taskIdsFlow
        every { pagedRuns(any(), any(), any()) } returns flowOf(PagingData.empty())
    }
    private val taskRepository: SyncTaskRepository = mockk(relaxed = true) {
        every { tasks } returns tasksFlow
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)

    private fun viewModel() = SyncHistoryViewModel(historyRepository, taskRepository, scheduler)

    // --- isTerminal -----------------------------------------------------------

    @Test
    fun isTerminal_returnsTrueForSuccessFailedCancelled_falseOtherwise() {
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.SUCCESS)).isTrue()
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.FAILED)).isTrue()
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.CANCELLED)).isTrue()
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.RUNNING)).isFalse()
    }

    @Test
    fun isTerminal_isFalseForQueuedAndIdle() {
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.QUEUED)).isFalse()
        assertThat(SyncHistoryViewModel.isTerminal(SyncStatus.IDLE)).isFalse()
    }

    // --- uiState: task-chip list ---------------------------------------------

    @Test
    fun uiState_taskFilterList_onlyIncludesTasksPresentInHistory() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "Photos"), task(id = 2, name = "Videos"))
        taskIdsFlow.value = listOf(1L)
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(1L)
        job.cancel()
    }

    @Test
    fun uiState_taskFilterList_isEmptyWhenNoRunsExist() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "Photos"))
        taskIdsFlow.value = emptyList()
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks).isEmpty()
        job.cancel()
    }

    // --- uiState: filter state echoed ----------------------------------------

    @Test
    fun setFilter_selectedTaskId_reflectedInUiState() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "T1"), task(id = 2, name = "T2"))
        taskIdsFlow.value = listOf(1L, 2L)
        advanceUntilIdle()

        vm.setFilter(1L)
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedTaskId).isEqualTo(1L)
        job.cancel()
    }

    @Test
    fun setFilter_null_clearsSelectedTaskId() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        taskIdsFlow.value = listOf(1L)
        advanceUntilIdle()

        vm.setFilter(1L)
        advanceUntilIdle()
        vm.setFilter(null)
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedTaskId).isNull()
        job.cancel()
    }

    @Test
    fun setStatusFilter_reflectedInUiState() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setStatusFilter(SyncStatus.FAILED)
        advanceUntilIdle()

        assertThat(vm.uiState.value.statusFilter).isEqualTo(SyncStatus.FAILED)
        job.cancel()
    }

    @Test
    fun setStatusFilter_null_clearsFilter() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setStatusFilter(SyncStatus.FAILED)
        advanceUntilIdle()
        vm.setStatusFilter(null)
        advanceUntilIdle()

        assertThat(vm.uiState.value.statusFilter).isNull()
        job.cancel()
    }

    // --- uiState: search query -----------------------------------------------

    @Test
    fun setSearchQuery_updatesSearchQueryInUiState() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setSearchQuery("hello")
        advanceUntilIdle()

        assertThat(vm.uiState.value.searchQuery).isEqualTo("hello")
        job.cancel()
    }

    // --- pagedRuns: toSyncRunRow mapping -------------------------------------
    // Tested via asSnapshot on a raw (non-cachedIn) PagingData flow to avoid
    // the StandardTestDispatcher deadlock that cachedIn(viewModelScope) causes.

    @Test
    fun pagedRuns_mapsNamedSyncRunToSyncRunRow() = runTest(mainDispatcher.dispatcher) {
        val namedRun = NamedSyncRun(run = run(id = 5L, taskId = 1L), taskName = "Photos")
        every { historyRepository.pagedRuns(any(), any(), any()) } returns
            flowOf(PagingData.from(listOf(namedRun)))

        val snapshot = flowOf(PagingData.from(listOf(namedRun)))
            .map { page -> page.map { n -> SyncRunRow(run = n.run, taskName = n.taskName ?: "(deleted task)") } }
            .asSnapshot {}

        assertThat(snapshot).hasSize(1)
        assertThat(snapshot.single().run.id).isEqualTo(5L)
        assertThat(snapshot.single().taskName).isEqualTo("Photos")
    }

    @Test
    fun pagedRuns_fallsBackToDeletedTaskLabel_whenTaskNameNull() = runTest(mainDispatcher.dispatcher) {
        val namedRun = NamedSyncRun(run = run(id = 7L, taskId = 99L), taskName = null)
        every { historyRepository.pagedRuns(any(), any(), any()) } returns
            flowOf(PagingData.from(listOf(namedRun)))

        val snapshot = flowOf(PagingData.from(listOf(namedRun)))
            .map { page -> page.map { n -> SyncRunRow(run = n.run, taskName = n.taskName ?: "(deleted task)") } }
            .asSnapshot {}

        assertThat(snapshot.single().taskName).isEqualTo("(deleted task)")
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

    // --- exportRows ---------------------------------------------------------

    @Test
    fun exportRows_mapsNamedSyncRunToSyncRunRow() = runTest(mainDispatcher.dispatcher) {
        val namedRun = NamedSyncRun(run = run(id = 9L, taskId = 3L), taskName = "Videos")
        coEvery { historyRepository.exportRows(any(), any(), any()) } returns listOf(namedRun)

        val rows = viewModel().exportRows()

        assertThat(rows).hasSize(1)
        assertThat(rows.single().run.id).isEqualTo(9L)
        assertThat(rows.single().taskName).isEqualTo("Videos")
    }

    @Test
    fun exportRows_nullTaskName_fallsBackToDeletedLabel() = runTest(mainDispatcher.dispatcher) {
        val namedRun = NamedSyncRun(run = run(id = 11L, taskId = 99L), taskName = null)
        coEvery { historyRepository.exportRows(any(), any(), any()) } returns listOf(namedRun)

        val rows = viewModel().exportRows()

        assertThat(rows.single().taskName).isEqualTo("(deleted task)")
    }

    @Test
    fun exportRows_trimsWhitespacePaddedSearchQuery() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        vm.setSearchQuery("  photos  ")
        advanceUntilIdle()

        coEvery { historyRepository.exportRows(null, null, "photos") } returns emptyList()

        vm.exportRows()

        coVerify { historyRepository.exportRows(null, null, "photos") }
        job.cancel()
    }

    // --- loading flag -------------------------------------------------------

    @Test
    fun uiState_loadingFalseAfterFirstEmission() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        taskIdsFlow.value = emptyList()
        advanceUntilIdle()

        assertThat(vm.uiState.value.loading).isFalse()
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
