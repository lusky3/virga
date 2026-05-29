package app.lusk.virga.feature.sync

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity
import com.google.common.truth.Truth.assertThat
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
class SyncHistoryViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val runsFlow = MutableStateFlow<List<SyncRunEntity>>(emptyList())
    private val tasksFlow = MutableStateFlow<List<SyncTaskEntity>>(emptyList())
    private val historyRepository: SyncHistoryRepository = mockk(relaxed = true) {
        every { recentRuns } returns runsFlow
    }
    private val taskRepository: SyncTaskRepository = mockk(relaxed = true) {
        every { tasks } returns tasksFlow
    }

    private fun viewModel() = SyncHistoryViewModel(historyRepository, taskRepository)

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

    private fun task(id: Long, name: String) = SyncTaskEntity(
        id = id,
        name = name,
        sourcePath = "/src",
        remoteName = "gdrive",
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
    )

    private fun run(id: Long, taskId: Long) = SyncRunEntity(
        id = id,
        taskId = taskId,
        startedAtEpochMs = 1_000L,
        status = SyncStatus.SUCCESS,
    )
}
