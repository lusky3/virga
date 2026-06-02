package app.lusk.virga.feature.sync

import android.content.Context
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.ConflictRepository
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.common.model.Conflict
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.sync.SyncProgressMonitor
import app.lusk.virga.sync.SyncScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerifyOrder
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
class SyncTasksViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val tasksFlow = MutableStateFlow<List<SyncTask>>(emptyList())
    private val runsFlow = MutableStateFlow<List<SyncRun>>(emptyList())
    private val taskRepository: SyncTaskRepository = mockk(relaxed = true) {
        every { tasks } returns tasksFlow
    }
    private val historyRepository: SyncHistoryRepository = mockk(relaxed = true) {
        every { recentRuns } returns runsFlow
    }
    private val conflictsFlow = MutableStateFlow<List<Conflict>>(emptyList())
    private val conflictRepository: ConflictRepository = mockk(relaxed = true) {
        every { unresolved } returns conflictsFlow
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)
    private val progressMonitor: SyncProgressMonitor = mockk(relaxed = true) {
        every { progressFor(any()) } returns flowOf(null)
    }
    // Resolve the snackbar message resources to their English text so the
    // message-content assertions below stay meaningful.
    private val context: Context = mockk(relaxed = true) {
        every { getString(R.string.sync_tasks_msg_task_duplicated) } returns "Task duplicated"
        every { getString(R.string.sync_tasks_msg_no_enabled_tasks) } returns "No enabled tasks"
    }

    private fun viewModel() =
        SyncTasksViewModel(context, taskRepository, historyRepository, conflictRepository, scheduler, progressMonitor)

    // --- pre-existing tests -------------------------------------------------

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
            taskRepository.save(match<SyncTask> { it.id == 1L && !it.enabled })
            scheduler.schedule(match<SyncTask> { it.id == 1L && !it.enabled })
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
        val job = backgroundScope.launch { vm.uiState.collect { } }
        tasksFlow.value = listOf(task(id = 1), task(id = 2))
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(vm.uiState.value.loading).isFalse()
        job.cancel()
    }

    // --- search filter ------------------------------------------------------

    @Test
    fun setSearch_matchesTaskName() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "Photos"), task(id = 2, name = "Music"))
        advanceUntilIdle()

        vm.setSearch("phot")
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.name }).containsExactly("Photos")
        job.cancel()
    }

    @Test
    fun setSearch_matchesRemoteName() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(
            task(id = 1, name = "T1", remoteName = "gdrive"),
            task(id = 2, name = "T2", remoteName = "dropbox"),
        )
        advanceUntilIdle()

        vm.setSearch("drop")
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(2L)
        job.cancel()
    }

    @Test
    fun setSearch_matchesSourcePath() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(
            task(id = 1, name = "T1", sourcePath = "/sdcard/DCIM"),
            task(id = 2, name = "T2", sourcePath = "/sdcard/Music"),
        )
        advanceUntilIdle()

        vm.setSearch("DCIM")
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(1L)
        job.cancel()
    }

    @Test
    fun setSearch_isCaseInsensitive() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "Photos"))
        advanceUntilIdle()

        vm.setSearch("PHOTOS")
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks).hasSize(1)
        job.cancel()
    }

    @Test
    fun setSearch_blankQueryReturnsAllTasks() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1), task(id = 2))
        advanceUntilIdle()
        vm.setSearch("nomatch")
        advanceUntilIdle()
        assertThat(vm.uiState.value.tasks).isEmpty()

        vm.setSearch("")
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks).hasSize(2)
        job.cancel()
    }

    // --- TaskFilter ---------------------------------------------------------

    @Test
    fun filterAll_showsAllTasks() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, enabled = true), task(id = 2, enabled = false))
        advanceUntilIdle()

        vm.setFilter(TaskFilter.ALL)
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks).hasSize(2)
        job.cancel()
    }

    @Test
    fun filterEnabled_showsOnlyEnabledTasks() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, enabled = true), task(id = 2, enabled = false))
        advanceUntilIdle()

        vm.setFilter(TaskFilter.ENABLED)
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(1L)
        job.cancel()
    }

    @Test
    fun filterFailing_showsOnlyTasksWithFailedLatestRun() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1), task(id = 2))
        runsFlow.value = listOf(
            run(id = 10, taskId = 1, status = SyncStatus.FAILED),
            run(id = 11, taskId = 2, status = SyncStatus.SUCCESS),
        )
        advanceUntilIdle()

        vm.setFilter(TaskFilter.FAILING)
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(1L)
        job.cancel()
    }

    @Test
    fun filterScheduled_showsOnlyTasksWithIntervalSet() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(
            task(id = 1, intervalMinutes = 60),
            task(id = 2, intervalMinutes = null),
        )
        advanceUntilIdle()

        vm.setFilter(TaskFilter.SCHEDULED)
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(1L)
        job.cancel()
    }

    // --- TaskSortOrder ------------------------------------------------------

    @Test
    fun sortByName_sortsAlphabeticallyAscending() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1, name = "Zebra"), task(id = 2, name = "Alpha"))
        advanceUntilIdle()

        vm.setSort(TaskSortOrder.NAME)
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.name }).containsExactly("Alpha", "Zebra").inOrder()
        job.cancel()
    }

    @Test
    fun sortByLastRun_mostRecentRunFirst() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1), task(id = 2))
        runsFlow.value = listOf(
            run(id = 10, taskId = 1, startedAt = 1000L),
            run(id = 11, taskId = 2, startedAt = 9000L),
        )
        advanceUntilIdle()

        vm.setSort(TaskSortOrder.LAST_RUN)
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(2L, 1L).inOrder()
        job.cancel()
    }

    @Test
    fun sortByLastRun_tasksWithNoRunSortLast() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1), task(id = 2))
        runsFlow.value = listOf(run(id = 10, taskId = 1, startedAt = 5000L))
        advanceUntilIdle()

        vm.setSort(TaskSortOrder.LAST_RUN)
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(1L, 2L).inOrder()
        job.cancel()
    }

    @Test
    fun sortByStatus_failingBeforeRunningBeforeOther() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1), task(id = 2), task(id = 3))
        runsFlow.value = listOf(
            run(id = 10, taskId = 1, status = SyncStatus.SUCCESS),
            run(id = 11, taskId = 2, status = SyncStatus.RUNNING),
            run(id = 12, taskId = 3, status = SyncStatus.FAILED),
        )
        advanceUntilIdle()

        vm.setSort(TaskSortOrder.STATUS)
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
        job.cancel()
    }

    // --- Selection ----------------------------------------------------------

    @Test
    fun toggleSelection_addsIdToSelectedSet() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleSelection(7L)
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedIds).containsExactly(7L)
        job.cancel()
    }

    @Test
    fun toggleSelection_removesIdWhenAlreadySelected() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleSelection(7L)
        vm.toggleSelection(7L)
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

    // --- Bulk actions -------------------------------------------------------

    @Test
    fun bulkRun_schedulesEachSelectedIdAndClearsSelection() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleSelection(1L)
        vm.toggleSelection(2L)
        vm.bulkRun()
        advanceUntilIdle()

        verify(exactly = 1) { scheduler.syncNow(1L) }
        verify(exactly = 1) { scheduler.syncNow(2L) }
        assertThat(vm.uiState.value.selectedIds).isEmpty()
        job.cancel()
    }

    @Test
    fun bulkSetEnabled_savesAndSchedulesEachTaskAndClearsSelection() = runTest(mainDispatcher.dispatcher) {
        val taskA = task(id = 1, enabled = false)
        val taskB = task(id = 2, enabled = false)
        coEvery { taskRepository.getTask(1L) } returns taskA
        coEvery { taskRepository.getTask(2L) } returns taskB
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleSelection(1L)
        vm.toggleSelection(2L)
        vm.bulkSetEnabled(true)
        advanceUntilIdle()

        coVerifyOrder {
            taskRepository.save(match<SyncTask> { it.id == 1L && it.enabled })
            scheduler.schedule(match<SyncTask> { it.id == 1L && it.enabled })
        }
        coVerifyOrder {
            taskRepository.save(match<SyncTask> { it.id == 2L && it.enabled })
            scheduler.schedule(match<SyncTask> { it.id == 2L && it.enabled })
        }
        assertThat(vm.uiState.value.selectedIds).isEmpty()
        job.cancel()
    }

    @Test
    fun confirmBulkDelete_cancelsAndDeletesEachTaskAndClearsSelection() = runTest(mainDispatcher.dispatcher) {
        val taskA = task(id = 3)
        val taskB = task(id = 4)
        coEvery { taskRepository.getTask(3L) } returns taskA
        coEvery { taskRepository.getTask(4L) } returns taskB
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.toggleSelection(3L)
        vm.toggleSelection(4L)
        vm.confirmBulkDelete()
        advanceUntilIdle()

        verify(exactly = 1) { scheduler.cancel(3L) }
        verify(exactly = 1) { scheduler.cancel(4L) }
        assertThat(vm.uiState.value.selectedIds).isEmpty()
        job.cancel()
    }

    // --- Duplicate ----------------------------------------------------------

    @Test
    fun duplicate_savesNewEntityWithIdZeroAndCopySuffix() = runTest(mainDispatcher.dispatcher) {
        val original = task(id = 5, name = "Photos")

        viewModel().duplicate(original)
        advanceUntilIdle()

        coVerifyOrder {
            taskRepository.save(match<SyncTask> { it.id == 0L && it.name == "Photos copy" })
        }
    }

    @Test
    fun duplicate_postsTaskDuplicatedMessage() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.duplicate(task(id = 1))
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).isEqualTo("Task duplicated")
        job.cancel()
    }

    // --- syncAllEnabled -----------------------------------------------------

    @Test
    fun syncAllEnabled_schedulesOnlyEnabledTasks() = runTest(mainDispatcher.dispatcher) {
        val enabled1 = task(id = 1, enabled = true)
        val enabled2 = task(id = 2, enabled = true)
        val disabled = task(id = 3, enabled = false)
        tasksFlow.value = listOf(enabled1, enabled2, disabled)

        viewModel().syncAllEnabled()
        advanceUntilIdle()

        verify(exactly = 1) { scheduler.syncNow(1L) }
        verify(exactly = 1) { scheduler.syncNow(2L) }
        verify(exactly = 0) { scheduler.syncNow(3L) }
    }

    @Test
    fun syncAllEnabled_whenNoEnabledTasks_postsNoEnabledTasksMessage() = runTest(mainDispatcher.dispatcher) {
        tasksFlow.value = listOf(task(id = 1, enabled = false))
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.syncAllEnabled()
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).isEqualTo("No enabled tasks")
        job.cancel()
    }

    // --- Swipe-to-delete ----------------------------------------------------

    @Test
    fun swipeDelete_hidesTaskFromList() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1), task(id = 2))
        advanceUntilIdle()

        vm.swipeDelete(task(id = 1))
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(2L)
        assertThat(vm.uiState.value.pendingDeleteTask?.id).isEqualTo(1L)
        job.cancel()
    }

    @Test
    fun undoSwipeDelete_restoresTaskToList() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1), task(id = 2))
        advanceUntilIdle()

        vm.swipeDelete(task(id = 1))
        advanceUntilIdle()
        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(2L)

        vm.undoSwipeDelete()
        advanceUntilIdle()

        assertThat(vm.uiState.value.tasks.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(vm.uiState.value.pendingDeleteTask).isNull()
        job.cancel()
    }

    @Test
    fun commitSwipeDelete_cancelsAndDeletesTask() = runTest(mainDispatcher.dispatcher) {
        val taskToDelete = task(id = 1)
        val vm = viewModel()
        vm.swipeDelete(taskToDelete)

        vm.commitSwipeDelete()
        advanceUntilIdle()

        coVerifyOrder {
            scheduler.cancel(1L)
            taskRepository.delete(taskToDelete)
        }
    }

    // --- unresolvedConflictCount / anyRunActive ----------------------------

    @Test
    fun unresolvedConflictCount_reflectsConflictRepositorySize() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        conflictsFlow.value = listOf(conflict(id = 1), conflict(id = 2), conflict(id = 3))
        advanceUntilIdle()

        assertThat(vm.uiState.value.unresolvedConflictCount).isEqualTo(3)
        job.cancel()
    }

    @Test
    fun unresolvedConflictCount_isZeroWhenNoConflicts() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        conflictsFlow.value = emptyList()
        advanceUntilIdle()

        assertThat(vm.uiState.value.unresolvedConflictCount).isEqualTo(0)
        job.cancel()
    }

    @Test
    fun anyRunActive_isTrueWhenARunIsRunning() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1))
        runsFlow.value = listOf(run(id = 10, taskId = 1, status = SyncStatus.RUNNING))
        advanceUntilIdle()

        assertThat(vm.uiState.value.anyRunActive).isTrue()
        job.cancel()
    }

    @Test
    fun anyRunActive_isTrueWhenARunIsQueued() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1))
        runsFlow.value = listOf(run(id = 10, taskId = 1, status = SyncStatus.QUEUED))
        advanceUntilIdle()

        assertThat(vm.uiState.value.anyRunActive).isTrue()
        job.cancel()
    }

    @Test
    fun anyRunActive_isFalseWhenLatestRunIsSuccess() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        tasksFlow.value = listOf(task(id = 1))
        runsFlow.value = listOf(run(id = 10, taskId = 1, status = SyncStatus.SUCCESS))
        advanceUntilIdle()

        assertThat(vm.uiState.value.anyRunActive).isFalse()
        job.cancel()
    }

    @Test
    fun anyRunActive_isFalseWhenNoRuns() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        runsFlow.value = emptyList()
        advanceUntilIdle()

        assertThat(vm.uiState.value.anyRunActive).isFalse()
        job.cancel()
    }

    // --- helpers ------------------------------------------------------------

    private fun task(
        id: Long,
        enabled: Boolean = true,
        name: String = "task-$id",
        remoteName: String = "gdrive",
        sourcePath: String = "/src",
        intervalMinutes: Int? = 60,
    ) = SyncTask(
        id = id,
        name = name,
        sourcePath = sourcePath,
        remoteName = remoteName,
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = intervalMinutes,
        enabled = enabled,
    )

    private fun run(
        id: Long,
        taskId: Long,
        status: SyncStatus = SyncStatus.SUCCESS,
        startedAt: Long = 1_000L,
    ) = SyncRun(
        id = id,
        taskId = taskId,
        startedAtEpochMs = startedAt,
        status = status,
    )

    private fun conflict(id: Long) = Conflict(
        id = id,
        taskId = 1,
        remoteName = "gdrive",
        basePath = "file.txt",
        variant1Path = "file.txt.conflict1",
        variant2Path = "file.txt.conflict2",
        variant1Size = 100,
        variant2Size = 200,
    )
}
