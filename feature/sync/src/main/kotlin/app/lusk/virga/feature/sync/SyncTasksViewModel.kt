package app.lusk.virga.feature.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.ConflictRepository
import app.lusk.virga.core.data.StatsRepository
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.sync.SyncProgressMonitor
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Overall home status derived from tasks + latest runs (BRAND §10).
 *
 * Priority: Running > NeedsAttention > UpToDate/Idle. All variants are pure
 * data — no Android types — so they are unit-testable without instrumentation.
 */
/** Task list filter presets. */
enum class TaskFilter { ALL, ENABLED, FAILING, SCHEDULED }

/** Task list sort orders. */
enum class TaskSortOrder { NAME, LAST_RUN, STATUS }

data class SyncTasksUiState(
    val tasks: List<SyncTask> = emptyList(),
    /** Map of taskId → most-recent run, for status badges and the at-a-glance line. */
    val latestRuns: Map<Long, SyncRun> = emptyMap(),
    val loading: Boolean = true,
    /** One-shot snackbar message to display (null = none pending). */
    val message: String? = null,
    val searchQuery: String = "",
    val activeFilter: TaskFilter = TaskFilter.ALL,
    val sortOrder: TaskSortOrder = TaskSortOrder.LAST_RUN,
    /** Non-empty => multi-select mode is active. */
    val selectedIds: Set<Long> = emptySet(),
    /** Task just swiped away and awaiting the Undo snackbar (hidden, not yet deleted). */
    val pendingDeleteTask: SyncTask? = null,
    /** Count of unresolved conflicts across all tasks (drives the top-bar badge). */
    val unresolvedConflictCount: Int = 0,
    /** True when any task has a RUNNING/QUEUED run (drives sync-all → cancel-all). */
    val anyRunActive: Boolean = false,
    /** Derived overall status for the home header hero (BRAND §10). */
    val homeStatus: HomeStatus = HomeStatus.Idle,
    /** Total bytes transferred across all lifetime runs (from StatsRepository). */
    val lifetimeBytes: Long = 0L,
    /** Total run count across all lifetime runs (from StatsRepository). */
    val lifetimeRuns: Long = 0L,
)

@HiltViewModel
class SyncTasksViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: SyncTaskRepository,
    private val historyRepository: SyncHistoryRepository,
    private val conflictRepository: ConflictRepository,
    private val statsRepository: StatsRepository,
    private val scheduler: SyncScheduler,
    private val progressMonitor: SyncProgressMonitor,
) : ViewModel() {

    /** UI control state (search/filter/sort/selection/pending-delete) held together. */
    private data class Controls(
        val query: String = "",
        val filter: TaskFilter = TaskFilter.ALL,
        val sort: TaskSortOrder = TaskSortOrder.LAST_RUN,
        val selected: Set<Long> = emptySet(),
        /** The single task swiped away and awaiting Undo — hidden but not yet deleted. */
        val pendingDelete: SyncTask? = null,
    )

    private val _controls = MutableStateFlow(Controls())
    private val _message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SyncTasksUiState> =
        combine(
            combine(
                taskRepository.tasks,
                historyRepository.recentRuns,
                conflictRepository.unresolved,
            ) { tasks, runs, conflicts -> Triple(tasks, runs, conflicts) },
            combine(
                _controls,
                _message,
                statsRepository.stats,
            ) { controls, msg, stats -> Triple(controls, msg, stats) },
        ) { (tasks, runs, conflicts), (controls, msg, stats) ->
            val latestRuns = runs
                .groupBy { it.taskId }
                .mapValues { (_, v) -> v.maxBy { it.startedAtEpochMs } }
            val visible = tasks
                .asSequence()
                .filterNot { it.id == controls.pendingDelete?.id }
                .filter { matchesFilter(it, controls.filter, latestRuns) }
                .filter { matchesQuery(it, controls.query) }
                .sortedWith(comparatorFor(controls.sort, latestRuns))
                .toList()
            SyncTasksUiState(
                tasks = visible,
                latestRuns = latestRuns,
                loading = false,
                message = msg,
                searchQuery = controls.query,
                activeFilter = controls.filter,
                sortOrder = controls.sort,
                selectedIds = controls.selected,
                pendingDeleteTask = controls.pendingDelete,
                unresolvedConflictCount = conflicts.size,
                anyRunActive = latestRuns.values.any {
                    it.status == SyncStatus.RUNNING || it.status == SyncStatus.QUEUED
                },
                homeStatus = deriveHomeStatus(tasks, latestRuns),
                lifetimeBytes = stats.totalBytesTransferred,
                lifetimeRuns = stats.totalRuns,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SyncTasksUiState(),
        )


    private fun matchesFilter(
        task: SyncTask,
        filter: TaskFilter,
        latestRuns: Map<Long, SyncRun>,
    ): Boolean = when (filter) {
        TaskFilter.ALL -> true
        TaskFilter.ENABLED -> task.enabled
        TaskFilter.FAILING -> latestRuns[task.id]?.status == SyncStatus.FAILED
        // A task is "scheduled" if it repeats on an interval OR on a calendar
        // (day-of-week) schedule. Calendar tasks persist intervalMinutes=null, so
        // the days-mask check is needed or they'd be missed here.
        TaskFilter.SCHEDULED -> task.intervalMinutes != null || task.scheduleDaysMask != 0
    }

    private fun matchesQuery(task: SyncTask, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return task.name.lowercase().contains(q) ||
            task.remoteName.lowercase().contains(q) ||
            task.sourcePath.lowercase().contains(q)
    }

    private fun comparatorFor(
        sort: TaskSortOrder,
        latestRuns: Map<Long, SyncRun>,
    ): Comparator<SyncTask> = when (sort) {
        TaskSortOrder.NAME -> compareBy { it.name.lowercase() }
        // Most-recent run first; tasks with no run sort last.
        TaskSortOrder.LAST_RUN -> compareByDescending { latestRuns[it.id]?.startedAtEpochMs ?: Long.MIN_VALUE }
        // Failing first, then running/queued, then the rest.
        TaskSortOrder.STATUS -> compareBy { statusRank(latestRuns[it.id]?.status) }
    }

    private fun statusRank(status: SyncStatus?): Int = when (status) {
        SyncStatus.FAILED -> 0
        SyncStatus.RUNNING, SyncStatus.QUEUED -> 1
        else -> 2
    }

    // --- Search / filter / sort --------------------------------------------------

    fun setSearch(query: String) = _controls.update { it.copy(query = query) }
    fun setFilter(filter: TaskFilter) = _controls.update { it.copy(filter = filter) }
    fun setSort(order: TaskSortOrder) = _controls.update { it.copy(sort = order) }

    // --- Single-task actions -----------------------------------------------------

    fun syncNow(taskId: Long) {
        scheduler.syncNow(taskId)
        _message.value = context.getString(R.string.sync_tasks_msg_sync_started)
    }

    fun cancelSync(taskId: Long) = scheduler.cancel(taskId)

    /** Live progress for a running task (WS1.1), or null when idle. */
    fun progressFor(taskId: Long): Flow<SyncProgress?> = progressMonitor.progressFor(taskId)

    fun clearMessage() { _message.value = null }

    fun setEnabled(task: SyncTask, enabled: Boolean) = viewModelScope.launch {
        val updated = task.copy(enabled = enabled)
        taskRepository.save(updated)
        scheduler.schedule(updated)
    }

    fun delete(task: SyncTask) = viewModelScope.launch {
        scheduler.cancel(task.id)
        taskRepository.delete(task)
    }

    /** Duplicate a task as a new disabled-id copy and open nothing (list refreshes). */
    fun duplicate(task: SyncTask) = viewModelScope.launch {
        taskRepository.save(task.copy(id = 0, name = "${task.name} copy"))
        _message.value = context.getString(R.string.sync_tasks_msg_task_duplicated)
    }

    /** Run every enabled task now. */
    fun syncAllEnabled() = viewModelScope.launch {
        val enabled = taskRepository.tasks.first().filter { it.enabled }
        enabled.forEach { scheduler.syncNow(it.id) }
        _message.value = if (enabled.isEmpty()) {
            context.getString(R.string.sync_tasks_msg_no_enabled_tasks)
        } else {
            context.resources.getQuantityString(R.plurals.sync_tasks_msg_syncing, enabled.size, enabled.size)
        }
    }

    // --- Swipe-to-delete (deferred, undoable) ------------------------------------
    //
    // A single pending row at a time. The screen drives the Undo snackbar from a
    // screen-level effect keyed on [SyncTasksUiState.pendingDeleteTask], so the
    // undo/commit decision is never tied to (and cancelled with) the swiped row's
    // own composition — the bug that previously left rows hidden-but-not-deleted
    // and made Undo a no-op.

    /** Swipe a row away (hide + await Undo). If another row is already pending,
     *  commit it first so it isn't left stuck hidden. */
    fun swipeDelete(task: SyncTask) {
        _controls.value.pendingDelete?.let { previous ->
            if (previous.id != task.id) deleteNow(previous)
        }
        _controls.update { it.copy(pendingDelete = task) }
    }

    /** Undo the pending swipe: just un-hide the row (nothing was deleted). */
    fun undoSwipeDelete() = _controls.update { it.copy(pendingDelete = null) }

    /** Commit the pending swipe: actually delete it. */
    fun commitSwipeDelete() {
        val task = _controls.value.pendingDelete ?: return
        _controls.update { it.copy(pendingDelete = null) }
        deleteNow(task)
    }

    private fun deleteNow(task: SyncTask) = viewModelScope.launch {
        scheduler.cancel(task.id)
        taskRepository.delete(task)
    }

    // --- Multi-select / bulk -----------------------------------------------------

    fun toggleSelection(id: Long) = _controls.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun clearSelection() = _controls.update { it.copy(selected = emptySet()) }

    fun bulkRun() {
        val ids = _controls.value.selected
        ids.forEach { scheduler.syncNow(it) }
        _message.value = context.resources.getQuantityString(R.plurals.sync_tasks_msg_syncing, ids.size, ids.size)
        clearSelection()
    }

    fun bulkSetEnabled(enabled: Boolean) = viewModelScope.launch {
        val ids = _controls.value.selected.toList()
        ids.forEach { id ->
            taskRepository.getTask(id)?.let { task ->
                val updated = task.copy(enabled = enabled)
                taskRepository.save(updated)
                scheduler.schedule(updated)
            }
        }
        clearSelection()
    }

    fun confirmBulkDelete() = viewModelScope.launch {
        val ids = _controls.value.selected.toList()
        ids.forEach { id ->
            taskRepository.getTask(id)?.let { task ->
                scheduler.cancel(id)
                taskRepository.delete(task)
            }
        }
        clearSelection()
    }
}
