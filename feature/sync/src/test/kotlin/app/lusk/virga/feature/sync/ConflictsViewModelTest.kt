package app.lusk.virga.feature.sync

import app.lusk.virga.core.data.ConflictChoice
import app.lusk.virga.core.data.ConflictRepository
import app.lusk.virga.core.database.entity.ConflictEntity
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class ConflictsViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val unresolvedFlow = MutableStateFlow<List<ConflictEntity>>(emptyList())
    private val repository: ConflictRepository = mockk(relaxed = true) {
        every { unresolved } returns unresolvedFlow
    }

    private fun viewModel() = ConflictsViewModel(repository)

    // --- pre-existing tests -------------------------------------------------

    @Test
    fun resolve_callsRepositoryWithChoice() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.resolve(any(), any()) } returns Result.success(Unit)
        val c = conflict(id = 7)

        viewModel().resolve(c, ConflictChoice.KEEP_VARIANT_1)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.resolve(c, ConflictChoice.KEEP_VARIANT_1) }
    }

    @Test
    fun resolve_failure_surfacesErrorMessage() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.resolve(any(), any()) } returns Result.failure(RuntimeException("nope"))
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.resolve(conflict(id = 1), ConflictChoice.KEEP_BOTH)
        advanceUntilIdle()

        assertThat(vm.uiState.value.error).isEqualTo("nope")
        assertThat(vm.uiState.value.resolvingId).isNull()
        job.cancel()
    }

    @Test
    fun clearError_dropsTransientErrorWithoutTouchingResolvingId() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.resolve(any(), any()) } returns Result.failure(RuntimeException("boom"))
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        vm.resolve(conflict(id = 2), ConflictChoice.KEEP_VARIANT_2)
        advanceUntilIdle()
        assertThat(vm.uiState.value.error).isEqualTo("boom")

        vm.clearError()
        advanceUntilIdle()

        assertThat(vm.uiState.value.error).isNull()
        job.cancel()
    }

    @Test
    fun uiState_mirrorsUnresolvedFlow() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        unresolvedFlow.value = listOf(conflict(id = 1), conflict(id = 2))
        advanceUntilIdle()

        assertThat(vm.uiState.value.conflicts.map { it.id }).containsExactly(1L, 2L)
        job.cancel()
    }

    // --- single resolution --------------------------------------------------

    @Test
    fun resolve_keepVariant2_callsRepositoryWithCorrectChoice() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.resolve(any(), any()) } returns Result.success(Unit)
        val c = conflict(id = 3)

        viewModel().resolve(c, ConflictChoice.KEEP_VARIANT_2)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.resolve(c, ConflictChoice.KEEP_VARIANT_2) }
    }

    @Test
    fun resolve_keepBoth_callsRepositoryWithKeepBothChoice() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.resolve(any(), any()) } returns Result.success(Unit)
        val c = conflict(id = 4)

        viewModel().resolve(c, ConflictChoice.KEEP_BOTH)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.resolve(c, ConflictChoice.KEEP_BOTH) }
    }

    // --- selection toggle ---------------------------------------------------

    @Test
    fun toggleSelection_addsConflictIdToSelectedSet() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleSelection(5L)
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedIds).containsExactly(5L)
        job.cancel()
    }

    @Test
    fun toggleSelection_removesIdWhenAlreadySelected() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleSelection(5L)
        vm.toggleSelection(5L)
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedIds).isEmpty()
        job.cancel()
    }

    @Test
    fun clearSelection_emptiesSelectedIds() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleSelection(1L)
        vm.toggleSelection(2L)
        vm.clearSelection()
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedIds).isEmpty()
        job.cancel()
    }

    // --- bulk resolution applies choice to selected items ------------------

    @Test
    fun confirmBulkChoice_appliesChoiceToSelectedConflicts() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.resolve(any(), any()) } returns Result.success(Unit)
        val c1 = conflict(id = 1)
        val c2 = conflict(id = 2)
        val c3 = conflict(id = 3)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        unresolvedFlow.value = listOf(c1, c2, c3)
        advanceUntilIdle()

        vm.toggleSelection(1L)
        vm.toggleSelection(2L)
        vm.requestBulkChoice(ConflictChoice.KEEP_VARIANT_1)
        vm.confirmBulkChoice()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.resolve(c1, ConflictChoice.KEEP_VARIANT_1) }
        coVerify(exactly = 1) { repository.resolve(c2, ConflictChoice.KEEP_VARIANT_1) }
        coVerify(exactly = 0) { repository.resolve(c3, any()) }
        job.cancel()
    }

    @Test
    fun confirmBulkChoice_appliesChoiceToAllConflictsWhenNoneSelected() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.resolve(any(), any()) } returns Result.success(Unit)
        val c1 = conflict(id = 1)
        val c2 = conflict(id = 2)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        unresolvedFlow.value = listOf(c1, c2)
        advanceUntilIdle()

        vm.requestBulkChoice(ConflictChoice.KEEP_VARIANT_2)
        vm.confirmBulkChoice()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.resolve(c1, ConflictChoice.KEEP_VARIANT_2) }
        coVerify(exactly = 1) { repository.resolve(c2, ConflictChoice.KEEP_VARIANT_2) }
        job.cancel()
    }

    @Test
    fun confirmBulkChoice_clearsSelectionAfterApplying() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.resolve(any(), any()) } returns Result.success(Unit)
        val c1 = conflict(id = 1)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        unresolvedFlow.value = listOf(c1)
        advanceUntilIdle()

        vm.toggleSelection(1L)
        vm.requestBulkChoice(ConflictChoice.KEEP_BOTH)
        vm.confirmBulkChoice()
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedIds).isEmpty()
        assertThat(vm.uiState.value.pendingBulkChoice).isNull()
        job.cancel()
    }

    @Test
    fun cancelBulkChoice_setsPendingBulkChoiceToNull() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.requestBulkChoice(ConflictChoice.KEEP_VARIANT_1)
        advanceUntilIdle()
        assertThat(vm.uiState.value.pendingBulkChoice).isEqualTo(ConflictChoice.KEEP_VARIANT_1)

        vm.cancelBulkChoice()
        advanceUntilIdle()

        assertThat(vm.uiState.value.pendingBulkChoice).isNull()
        job.cancel()
    }

    // --- helpers ------------------------------------------------------------

    private fun conflict(id: Long) = ConflictEntity(
        id = id,
        taskId = 1,
        remoteName = "gdrive",
        basePath = "Docs/report.txt",
        variant1Path = "Docs/report.txt.conflict1",
        variant2Path = "Docs/report.txt.conflict2",
        variant1Size = 100,
        variant2Size = 200,
    )
}
