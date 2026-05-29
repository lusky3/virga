package app.lusk.virga.feature.sync

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity
import app.lusk.virga.sync.SyncScheduler
import com.google.common.truth.Truth.assertThat
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
class SyncTasksViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val tasksFlow = MutableStateFlow<List<SyncTaskEntity>>(emptyList())
    private val runsFlow = MutableStateFlow<List<SyncRunEntity>>(emptyList())
    private val taskRepository: SyncTaskRepository = mockk(relaxed = true) {
        every { tasks } returns tasksFlow
    }
    private val historyRepository: SyncHistoryRepository = mockk(relaxed = true) {
        every { recentRuns } returns runsFlow
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)

    private fun viewModel() = SyncTasksViewModel(taskRepository, historyRepository, scheduler)

    @Test
    fun syncNow_delegatesToScheduler() {
        viewModel().syncNow(taskId = 42L)

        verify(exactly = 1) { scheduler.syncNow(42L) }
    }

    @Test
    fun setEnabled_savesUpdatedCopyAndReschedules() = runTest(mainDispatcher.dispatcher) {
        val task = task(id = 1, enabled = true)

        viewModel().setEnabled(task, enabled = false)
        advanceUntilIdle()

        coVerifyOrder {
            taskRepository.save(match<SyncTaskEntity> { it.id == 1L && !it.enabled })
            scheduler.schedule(match<SyncTaskEntity> { it.id == 1L && !it.enabled })
        }
    }

    @Test
    fun delete_cancelsBeforeRemoving() = runTest(mainDispatcher.dispatcher) {
        val task = task(id = 5)

        viewModel().delete(task)
        advanceUntilIdle()

        coVerifyOrder {
            scheduler.cancel(5L)
            taskRepository.delete(task)
        }
    }

    @Test
    fun uiState_reflectsRepositoryTasks() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        // Subscribe so the WhileSubscribed upstream actually starts collecting.
        val job = backgroundScope.launch { vm.uiState.collect { } }
        tasksFlow.value = listOf(task(id = 1), task(id = 2))
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(vm.uiState.value.loading).isFalse()
        job.cancel()
    }

    private fun task(id: Long, enabled: Boolean = true) = SyncTaskEntity(
        id = id,
        name = "task-$id",
        sourcePath = "/src",
        remoteName = "gdrive",
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = 60,
        enabled = enabled,
    )
}
