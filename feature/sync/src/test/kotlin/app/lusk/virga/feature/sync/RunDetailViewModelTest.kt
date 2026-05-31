package app.lusk.virga.feature.sync

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncTask
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
class RunDetailViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val runFlow = MutableStateFlow<SyncRun?>(null)
    private val taskFlow = MutableStateFlow<SyncTask?>(null)

    private val historyRepository: SyncHistoryRepository = mockk(relaxed = true) {
        every { observeRun(any()) } returns runFlow
    }
    private val taskRepository: SyncTaskRepository = mockk(relaxed = true) {
        every { task(any()) } returns taskFlow
    }

    private fun viewModel() = RunDetailViewModel(historyRepository, taskRepository)

    // --- load(id) emits run + taskName -------------------------------------

    @Test
    fun load_thenUiState_emitsRunWithTaskName() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.load(id = 10L)
        runFlow.value = run(id = 10L, taskId = 1L)
        taskFlow.value = task(id = 1L, name = "Photos")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.run).isNotNull()
        assertThat(state.run!!.id).isEqualTo(10L)
        assertThat(state.taskName).isEqualTo("Photos")
        assertThat(state.loading).isFalse()
        job.cancel()
    }

    @Test
    fun load_thenUiState_emitsEmptyTaskNameWhenTaskIsNull() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.load(id = 10L)
        runFlow.value = run(id = 10L, taskId = 99L)
        taskFlow.value = null
        advanceUntilIdle()

        assertThat(vm.uiState.value.taskName).isEmpty()
        assertThat(vm.uiState.value.loading).isFalse()
        job.cancel()
    }

    // --- null run → loading=false, run=null --------------------------------

    @Test
    fun load_nullRun_loadingFalseAndRunIsNull() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.load(id = 5L)
        runFlow.value = null
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.loading).isFalse()
        assertThat(state.run).isNull()
        job.cancel()
    }

    // --- idempotency of load -----------------------------------------------

    @Test
    fun load_calledTwiceWithSameId_doesNotRestartCollection() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.load(id = 3L)
        runFlow.value = run(id = 3L, taskId = 1L)
        taskFlow.value = task(id = 1L, name = "Music")
        advanceUntilIdle()
        val nameAfterFirst = vm.uiState.value.taskName

        vm.load(id = 3L)
        advanceUntilIdle()

        assertThat(vm.uiState.value.taskName).isEqualTo(nameAfterFirst)
        job.cancel()
    }

    // --- initial state -------------------------------------------------------

    @Test
    fun uiState_initialState_isLoadingWithNullRun() {
        val vm = viewModel()

        val state = vm.uiState.value
        assertThat(state.loading).isTrue()
        assertThat(state.run).isNull()
        assertThat(state.taskName).isEmpty()
    }

    // --- run status flows through ------------------------------------------

    @Test
    fun load_runWithFailedStatus_statusIsPreservedInUiState() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.load(id = 20L)
        runFlow.value = run(id = 20L, taskId = 2L, status = SyncStatus.FAILED)
        taskFlow.value = task(id = 2L, name = "Backup")
        advanceUntilIdle()

        assertThat(vm.uiState.value.run!!.status).isEqualTo(SyncStatus.FAILED)
        job.cancel()
    }

    // --- helpers ------------------------------------------------------------

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

    private fun task(id: Long, name: String) = SyncTask(
        id = id,
        name = name,
        sourcePath = "/src",
        remoteName = "gdrive",
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
    )
}
