package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A run row joined with its task's display name for the history list. */
data class SyncRunRow(
    val run: SyncRunEntity,
    val taskName: String,
)

/** A task option offered in the history filter row. */
data class HistoryTaskFilter(
    val id: Long,
    val name: String,
)

data class SyncHistoryUiState(
    val rows: List<SyncRunRow> = emptyList(),
    val tasks: List<HistoryTaskFilter> = emptyList(),
    val selectedTaskId: Long? = null,
    val statusFilter: SyncStatus? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class SyncHistoryViewModel @Inject constructor(
    private val historyRepository: SyncHistoryRepository,
    taskRepository: SyncTaskRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    private val selectedTaskId = MutableStateFlow<Long?>(null)
    private val statusFilter = MutableStateFlow<SyncStatus?>(null)

    val uiState: StateFlow<SyncHistoryUiState> =
        combine(
            historyRepository.recentRuns,
            taskRepository.tasks,
            selectedTaskId,
            statusFilter,
        ) { runs, tasks, filterId, statusF ->
            val names = tasks.associate { it.id to it.name }
            val taskIdsInHistory = runs.mapTo(mutableSetOf()) { it.taskId }
            val filtered = runs
                .let { if (filterId == null) it else it.filter { r -> r.taskId == filterId } }
                .let { if (statusF == null) it else it.filter { r -> r.status == statusF } }
            SyncHistoryUiState(
                rows = filtered.map { SyncRunRow(it, names[it.taskId] ?: "(deleted task)") },
                tasks = tasks.filter { it.id in taskIdsInHistory }
                    .map { HistoryTaskFilter(it.id, it.name) },
                selectedTaskId = filterId,
                statusFilter = statusF,
                loading = false,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SyncHistoryUiState(),
        )

    fun setFilter(taskId: Long?) { selectedTaskId.value = taskId }

    fun setStatusFilter(status: SyncStatus?) { statusFilter.value = status }

    fun clearHistory() = viewModelScope.launch {
        historyRepository.clearAll()
    }

    fun retryRun(run: SyncRunEntity) {
        scheduler.syncNow(run.taskId)
    }

    companion object {
        fun isTerminal(status: SyncStatus): Boolean =
            status == SyncStatus.SUCCESS || status == SyncStatus.FAILED || status == SyncStatus.CANCELLED
    }
}

