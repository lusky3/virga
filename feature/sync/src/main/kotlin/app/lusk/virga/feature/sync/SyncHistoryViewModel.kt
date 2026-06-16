package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import app.lusk.virga.core.common.model.NamedSyncRun
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A run row joined with its task's display name for the history list. */
data class SyncRunRow(
    val run: SyncRun,
    val taskName: String,
)

/** A task option offered in the history filter row. */
data class HistoryTaskFilter(
    val id: Long,
    val name: String,
)

data class SyncHistoryUiState(
    val tasks: List<HistoryTaskFilter> = emptyList(),
    val selectedTaskId: Long? = null,
    val statusFilter: SyncStatus? = null,
    val searchQuery: String = "",
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SyncHistoryViewModel @Inject constructor(
    private val historyRepository: SyncHistoryRepository,
    taskRepository: SyncTaskRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    private val selectedTaskId = MutableStateFlow<Long?>(null)
    private val statusFilter = MutableStateFlow<SyncStatus?>(null)
    private val searchQuery = MutableStateFlow("")

    val uiState: StateFlow<SyncHistoryUiState> =
        combine(
            historyRepository.distinctTaskIds,
            taskRepository.tasks,
            selectedTaskId,
            statusFilter,
            searchQuery,
        ) { taskIdsInHistory, tasks, filterId, statusF, query ->
            // The list itself is driven by [pagedRuns] (DB-level filter/search); this
            // state only carries the filter-chip options + current selections + loading.
            val idSet = taskIdsInHistory.toHashSet()
            SyncHistoryUiState(
                tasks = tasks.filter { it.id in idSet }
                    .map { HistoryTaskFilter(it.id, it.name) },
                selectedTaskId = filterId,
                statusFilter = statusF,
                searchQuery = query,
                loading = false,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SyncHistoryUiState(),
        )

    val pagedRuns: Flow<PagingData<SyncRunRow>> =
        combine(selectedTaskId, statusFilter, searchQuery) { taskId, status, query ->
            Triple(taskId, status, query)
        }.flatMapLatest { (taskId, status, query) ->
            historyRepository.pagedRuns(taskId, status, query.trim())
                .map { page -> page.map { it.toSyncRunRow() } }
        }.cachedIn(viewModelScope)

    fun setFilter(taskId: Long?) { selectedTaskId.value = taskId }

    fun setStatusFilter(status: SyncStatus?) { statusFilter.value = status }

    fun setSearchQuery(query: String) { searchQuery.value = query }

    fun clearHistory() = viewModelScope.launch {
        historyRepository.clearAll()
    }

    fun retryRun(run: SyncRun) {
        scheduler.syncNow(run.taskId)
    }

    suspend fun exportRows(): List<SyncRunRow> =
        historyRepository.exportRows(selectedTaskId.value, statusFilter.value, searchQuery.value.trim())
            .map { it.toSyncRunRow() }

    companion object {
        fun isTerminal(status: SyncStatus): Boolean =
            status == SyncStatus.SUCCESS || status == SyncStatus.FAILED || status == SyncStatus.CANCELLED
    }
}

private fun NamedSyncRun.toSyncRunRow() = SyncRunRow(
    run = run,
    taskName = taskName ?: "(deleted task)",
)
