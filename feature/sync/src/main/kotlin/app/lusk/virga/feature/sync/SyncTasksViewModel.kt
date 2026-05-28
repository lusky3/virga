package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.SyncTaskEntity
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncTasksUiState(
    val tasks: List<SyncTaskEntity> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class SyncTasksViewModel @Inject constructor(
    private val taskRepository: SyncTaskRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    val uiState: StateFlow<SyncTasksUiState> =
        taskRepository.tasks
            .map { SyncTasksUiState(tasks = it, loading = false) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SyncTasksUiState(),
            )

    fun syncNow(taskId: Long) = scheduler.syncNow(taskId)

    fun setEnabled(task: SyncTaskEntity, enabled: Boolean) = viewModelScope.launch {
        val updated = task.copy(enabled = enabled)
        taskRepository.save(updated)
        scheduler.schedule(updated)
    }

    fun delete(task: SyncTaskEntity) = viewModelScope.launch {
        scheduler.cancel(task.id)
        taskRepository.delete(task)
    }
}
