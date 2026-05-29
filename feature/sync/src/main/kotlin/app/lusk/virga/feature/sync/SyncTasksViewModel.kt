package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncTasksUiState(
    val tasks: List<SyncTaskEntity> = emptyList(),
    /** Map of taskId → most-recent run, for status badges. */
    val latestRuns: Map<Long, SyncRunEntity> = emptyMap(),
    val loading: Boolean = true,
    /** One-shot snackbar message to display (null = none pending). */
    val message: String? = null,
)

@HiltViewModel
class SyncTasksViewModel @Inject constructor(
    private val taskRepository: SyncTaskRepository,
    private val historyRepository: SyncHistoryRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SyncTasksUiState> =
        combine(
            taskRepository.tasks,
            historyRepository.recentRuns,
            _message,
        ) { tasks, runs, msg ->
            val latestRuns = runs
                .groupBy { it.taskId }
                .mapValues { (_, v) -> v.maxBy { it.startedAtEpochMs } }
            SyncTasksUiState(tasks = tasks, latestRuns = latestRuns, loading = false, message = msg)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SyncTasksUiState(),
        )

    fun syncNow(taskId: Long) {
        scheduler.syncNow(taskId)
        _message.value = "Sync started"
    }

    fun clearMessage() {
        _message.value = null
    }

    fun setEnabled(task: SyncTaskEntity, enabled: Boolean) = viewModelScope.launch {
        val updated = task.copy(enabled = enabled)
        taskRepository.save(updated)
        scheduler.schedule(updated)
    }

    fun delete(task: SyncTaskEntity) = viewModelScope.launch {
        scheduler.cancel(task.id)
        taskRepository.delete(task)
    }

    fun cancelSync(taskId: Long) {
        scheduler.cancel(taskId)
    }
}
