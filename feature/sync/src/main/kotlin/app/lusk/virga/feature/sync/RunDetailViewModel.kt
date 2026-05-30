package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.SyncRunEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class RunDetailUiState(
    val run: SyncRunEntity? = null,
    val taskName: String = "",
    val loading: Boolean = true,
)

/**
 * Loads a single [SyncRunEntity] for the run-detail screen. The run id is
 * supplied by the screen via [load] (Navigation 3 passes it as a composable
 * argument, not through `SavedStateHandle`). The task name is resolved by
 * observing the single owning task row rather than scanning the whole table.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RunDetailViewModel @Inject constructor(
    private val historyRepository: SyncHistoryRepository,
    private val taskRepository: SyncTaskRepository,
) : ViewModel() {

    private val runId = MutableStateFlow<Long?>(null)

    /** Idempotent: starts observing the given run. Safe to call from a `LaunchedEffect`. */
    fun load(id: Long) {
        if (runId.value != id) runId.value = id
    }

    val uiState: StateFlow<RunDetailUiState> =
        runId.filterNotNull()
            .flatMapLatest { id ->
                historyRepository.observeRun(id).flatMapLatest { run ->
                    if (run == null) {
                        flowOf(RunDetailUiState(run = null, loading = false))
                    } else {
                        taskRepository.task(run.taskId).map { task ->
                            RunDetailUiState(
                                run = run,
                                taskName = task?.name ?: "",
                                loading = false,
                            )
                        }
                    }
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                RunDetailUiState(),
            )
}
