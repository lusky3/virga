package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.SyncRunEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A run row joined with its task's display name for the history list. */
data class SyncRunRow(
    val run: SyncRunEntity,
    val taskName: String,
)

/** A task option offered in the history filter row. */
data class TaskFilter(
    val id: Long,
    val name: String,
)

data class SyncHistoryUiState(
    val rows: List<SyncRunRow> = emptyList(),
    val tasks: List<TaskFilter> = emptyList(),
    val selectedTaskId: Long? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class SyncHistoryViewModel @Inject constructor(
    historyRepository: SyncHistoryRepository,
    taskRepository: SyncTaskRepository,
) : ViewModel() {

    // null = show runs for all tasks; otherwise filter to this task id.
    private val selectedTaskId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<SyncHistoryUiState> =
        combine(
            historyRepository.recentRuns,
            taskRepository.tasks,
            selectedTaskId,
        ) { runs, tasks, filterId ->
            val names = tasks.associate { it.id to it.name }
            // Only offer a filter chip for tasks that actually appear in history.
            val taskIdsInHistory = runs.mapTo(mutableSetOf()) { it.taskId }
            val visibleRuns = if (filterId == null) runs else runs.filter { it.taskId == filterId }
            SyncHistoryUiState(
                rows = visibleRuns.map { SyncRunRow(it, names[it.taskId] ?: "(deleted task)") },
                tasks = tasks.filter { it.id in taskIdsInHistory }
                    .map { TaskFilter(it.id, it.name) },
                selectedTaskId = filterId,
                loading = false,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SyncHistoryUiState(),
        )

    /** Filter the history list to a single task, or pass null to show all. */
    fun setFilter(taskId: Long?) {
        selectedTaskId.value = taskId
    }

    companion object {
        fun isTerminal(status: SyncStatus): Boolean =
            status == SyncStatus.SUCCESS || status == SyncStatus.FAILED || status == SyncStatus.CANCELLED
    }
}
