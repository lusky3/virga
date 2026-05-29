package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.SyncRunEntity
import dagger.hilt.android.lifecycle.HiltViewModel
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

data class SyncHistoryUiState(
    val rows: List<SyncRunRow> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class SyncHistoryViewModel @Inject constructor(
    historyRepository: SyncHistoryRepository,
    taskRepository: SyncTaskRepository,
) : ViewModel() {

    val uiState: StateFlow<SyncHistoryUiState> =
        combine(historyRepository.recentRuns, taskRepository.tasks) { runs, tasks ->
            val names = tasks.associate { it.id to it.name }
            SyncHistoryUiState(
                rows = runs.map { SyncRunRow(it, names[it.taskId] ?: "(deleted task)") },
                loading = false,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SyncHistoryUiState(),
        )

    companion object {
        fun isTerminal(status: SyncStatus): Boolean =
            status == SyncStatus.SUCCESS || status == SyncStatus.FAILED || status == SyncStatus.CANCELLED
    }
}
