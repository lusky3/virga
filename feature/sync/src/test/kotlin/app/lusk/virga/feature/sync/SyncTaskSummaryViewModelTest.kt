package app.lusk.virga.feature.sync

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.sync.DryRunResult
import app.lusk.virga.sync.DryRunUseCase
import app.lusk.virga.sync.SyncProgressMonitor
import app.lusk.virga.sync.SyncScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
class SyncTaskSummaryViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val taskFlow = MutableStateFlow<SyncTask?>(null)
    private val runsFlow = MutableStateFlow<List<SyncRun>>(emptyList())
    private val progressFlow = MutableStateFlow<SyncProgress?>(null)

    private val taskRepository: SyncTaskRepository = mockk(relaxed = true) {
        every { task(any()) } returns taskFlow
    }
    private val historyRepository: SyncHistoryRepository = mockk(relaxed = true) {
        every { runsForTask(any()) } returns runsFlow
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)
    private val progressMonitor: SyncProgressMonitor = mockk(relaxed = true) {
        every { progressFor(any()) } returns progressFlow
    }
    private val dryRunUseCase: DryRunUseCase = mockk(relaxed = true)

    private fun viewModel() =
        SyncTaskSummaryViewModel(taskRepository, historyRepository, scheduler, progressMonitor, dryRunUseCase)

    // --- initial state -----------------------------------------------------

    @Test
    fun uiState_initialValue_isLoadingWithNoTask() {
        val vm = viewModel()

        val state = vm.uiState.value
        assertThat(state.loading).isTrue()
        assertThat(state.task).isNull()
        assertThat(state.runs).isEmpty()
        assertThat(state.liveProgress).isNull()
    }

    @Test
    fun dryRun_initialValue_isNotRunningWithNoResult() {
        val vm = viewModel()

        assertThat(vm.dryRun.value.running).isFalse()
        assertThat(vm.dryRun.value.result).isNull()
    }

    // --- load emits combined state -----------------------------------------

    @Test
    fun load_thenUiState_combinesTaskRunsAndProgress() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.load(id = 7L)
        taskFlow.value = task(id = 7L)
        runsFlow.value = listOf(run(id = 1L, taskId = 7L), run(id = 2L, taskId = 7L))
        progressFlow.value = progress(transferred = 50, total = 100)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.loading).isFalse()
        assertThat(state.task?.id).isEqualTo(7L)
        assertThat(state.runs).hasSize(2)
        assertThat(state.liveProgress?.transferredFiles).isEqualTo(50)
        job.cancel()
    }

    @Test
    fun load_taskIsNull_stillClearsLoading() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.load(id = 9L)
        taskFlow.value = null
        advanceUntilIdle()

        // Even a deleted/missing task resolves loading=false (with task=null).
        assertThat(vm.uiState.value.loading).isFalse()
        assertThat(vm.uiState.value.task).isNull()
        job.cancel()
    }

    @Test
    fun load_calledTwiceWithSameId_keepsObservingSameTask() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.load(id = 3L)
        taskFlow.value = task(id = 3L, name = "Music")
        advanceUntilIdle()
        val nameAfterFirst = vm.uiState.value.task?.name

        vm.load(id = 3L)
        advanceUntilIdle()

        assertThat(vm.uiState.value.task?.name).isEqualTo(nameAfterFirst)
        verify(exactly = 1) { taskRepository.task(3L) }
        job.cancel()
    }

    // --- syncNow / cancelSync use the loaded id ----------------------------

    @Test
    fun syncNow_beforeLoad_doesNothing() {
        val vm = viewModel()

        vm.syncNow()

        verify(exactly = 0) { scheduler.syncNow(any()) }
    }

    @Test
    fun syncNow_afterLoad_triggersSchedulerForLoadedId() {
        val vm = viewModel()

        vm.load(id = 42L)
        vm.syncNow()

        verify(exactly = 1) { scheduler.syncNow(42L) }
    }

    @Test
    fun cancelSync_afterLoad_cancelsLoadedId() {
        val vm = viewModel()

        vm.load(id = 42L)
        vm.cancelSync()

        verify(exactly = 1) { scheduler.cancel(42L) }
    }

    // --- setEnabled saves + reschedules ------------------------------------

    @Test
    fun setEnabled_savesUpdatedTaskAndReschedules() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.load(id = 7L)
        taskFlow.value = task(id = 7L, enabled = true)
        advanceUntilIdle()

        vm.setEnabled(false)
        advanceUntilIdle()

        coVerifyOrder {
            taskRepository.save(match<SyncTask> { it.id == 7L && !it.enabled })
            scheduler.schedule(match<SyncTask> { it.id == 7L && !it.enabled })
        }
        job.cancel()
    }

    @Test
    fun setEnabled_noTaskLoaded_doesNothing() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()

        vm.setEnabled(true)
        advanceUntilIdle()

        coVerify(exactly = 0) { taskRepository.save(any()) }
        coVerify(exactly = 0) { scheduler.schedule(any()) }
    }

    // --- delete cancels, deletes, then notifies ----------------------------

    @Test
    fun delete_cancelsDeletesAndInvokesCallback() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.load(id = 7L)
        taskFlow.value = task(id = 7L)
        advanceUntilIdle()

        var deleted = false
        vm.delete { deleted = true }
        advanceUntilIdle()

        assertThat(deleted).isTrue()
        coVerifyOrder {
            scheduler.cancel(7L)
            taskRepository.delete(match<SyncTask> { it.id == 7L })
        }
        job.cancel()
    }

    @Test
    fun delete_noTaskLoaded_doesNotInvokeCallback() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()

        var deleted = false
        vm.delete { deleted = true }
        advanceUntilIdle()

        assertThat(deleted).isFalse()
        coVerify(exactly = 0) { taskRepository.delete(any()) }
    }

    // --- previewAvailable delegates to DryRunUseCase -----------------------

    @Test
    fun previewAvailable_falseWhenNoTaskLoaded() {
        val vm = viewModel()

        assertThat(vm.previewAvailable()).isFalse()
    }

    @Test
    fun previewAvailable_reflectsUseCaseForLoadedTask() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val t = task(id = 7L)
        every { dryRunUseCase.isAvailableFor(t) } returns true

        vm.load(id = 7L)
        taskFlow.value = t
        advanceUntilIdle()

        assertThat(vm.previewAvailable()).isTrue()
        job.cancel()
    }

    // --- previewChanges populates dryRun state -----------------------------

    @Test
    fun previewChanges_runsPreviewAndStoresResult() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val t = task(id = 7L)
        val result = DryRunResult(filesToTransfer = 3, bytesToTransfer = 1_024L, errors = 0)
        every { dryRunUseCase.isAvailableFor(t) } returns true
        coEvery { dryRunUseCase.preview(t) } returns result

        vm.load(id = 7L)
        taskFlow.value = t
        advanceUntilIdle()

        vm.previewChanges()
        advanceUntilIdle()

        assertThat(vm.dryRun.value.running).isFalse()
        assertThat(vm.dryRun.value.result).isEqualTo(result)
        coVerify(exactly = 1) { dryRunUseCase.preview(t) }
        job.cancel()
    }

    @Test
    fun previewChanges_whenUnavailable_doesNotRunPreview() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val t = task(id = 7L)
        every { dryRunUseCase.isAvailableFor(t) } returns false

        vm.load(id = 7L)
        taskFlow.value = t
        advanceUntilIdle()

        vm.previewChanges()
        advanceUntilIdle()

        assertThat(vm.dryRun.value.running).isFalse()
        assertThat(vm.dryRun.value.result).isNull()
        coVerify(exactly = 0) { dryRunUseCase.preview(any()) }
        job.cancel()
    }

    @Test
    fun previewChanges_noTaskLoaded_doesNothing() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()

        vm.previewChanges()
        advanceUntilIdle()

        assertThat(vm.dryRun.value.running).isFalse()
        coVerify(exactly = 0) { dryRunUseCase.preview(any()) }
    }

    @Test
    fun dismissPreview_resetsDryRunState() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val t = task(id = 7L)
        every { dryRunUseCase.isAvailableFor(t) } returns true
        coEvery { dryRunUseCase.preview(t) } returns DryRunResult(1, 1L, 0)

        vm.load(id = 7L)
        taskFlow.value = t
        advanceUntilIdle()
        vm.previewChanges()
        advanceUntilIdle()
        assertThat(vm.dryRun.value.result).isNotNull()

        vm.dismissPreview()

        assertThat(vm.dryRun.value.running).isFalse()
        assertThat(vm.dryRun.value.result).isNull()
        job.cancel()
    }

    // --- helpers -----------------------------------------------------------

    private fun task(id: Long, name: String = "task-$id", enabled: Boolean = true) = SyncTask(
        id = id,
        name = name,
        sourcePath = "/src",
        remoteName = "gdrive",
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
        enabled = enabled,
    )

    private fun run(id: Long, taskId: Long) = SyncRun(
        id = id,
        taskId = taskId,
        startedAtEpochMs = 1_000L,
        status = SyncStatus.SUCCESS,
    )

    private fun progress(transferred: Int, total: Int) = SyncProgress(
        bytesTransferred = 0L,
        totalBytes = 0L,
        speedBytesPerSec = 0.0,
        transferredFiles = transferred,
        totalFiles = total,
        etaSeconds = null,
        errors = 0,
    )
}
