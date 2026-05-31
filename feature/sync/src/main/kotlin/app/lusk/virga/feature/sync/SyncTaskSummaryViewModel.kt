package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.sync.SyncProgressMonitor
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncTaskSummaryUiState(
    val task: SyncTask? = null,
    val runs: List<SyncRun> = emptyList(),
    val liveProgress: SyncProgress? = null,
    val loading: Boolean = true,
)

/**
 * Read-only overview of a single sync task: its configuration plus recent run
 * history. Tapping a task in the list lands here (rather than jumping straight
 * into the editor); Edit/Run/Delete are offered as explicit actions.
 *
 * The task id is supplied by the screen via [load] (Navigation 3 passes it as a
 * composable argument, not through `SavedStateHandle`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SyncTaskSummaryViewModel @Inject constructor(
    private val taskRepository: SyncTaskRepository,
    private val historyRepository: SyncHistoryRepository,
    private val scheduler: SyncScheduler,
    private val progressMonitor: SyncProgressMonitor,
) : ViewModel() {

    private val taskId = MutableStateFlow<Long?>(null)

    /** Idempotent: starts observing the given task. Safe to call from a `LaunchedEffect`. */
    fun load(id: Long) {
        if (taskId.value != id) taskId.value = id
    }

    val uiState: StateFlow<SyncTaskSummaryUiState> =
        taskId.filterNotNull()
            .flatMapLatest { id ->
                combine(
                    taskRepository.task(id),
                    historyRepository.runsForTask(id),
                    progressMonitor.progressFor(id),
                ) { task, runs, progress ->
                    SyncTaskSummaryUiState(task = task, runs = runs, liveProgress = progress, loading = false)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncTaskSummaryUiState())

    fun syncNow() {
        taskId.value?.let { scheduler.syncNow(it) }
    }

    fun cancelSync() {
        taskId.value?.let { scheduler.cancel(it) }
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        val task = uiState.value.task ?: return@launch
        val updated = task.copy(enabled = enabled)
        taskRepository.save(updated)
        scheduler.schedule(updated)
    }

    fun delete(onDeleted: () -> Unit) = viewModelScope.launch {
        val task = uiState.value.task ?: return@launch
        scheduler.cancel(task.id)
        taskRepository.delete(task)
        onDeleted()
    }
}
