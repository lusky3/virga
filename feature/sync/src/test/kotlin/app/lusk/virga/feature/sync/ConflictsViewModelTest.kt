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
