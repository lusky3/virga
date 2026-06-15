package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.sync.CheckResult
import app.lusk.virga.sync.CheckUseCase
import app.lusk.virga.sync.DryRunResult
import app.lusk.virga.sync.DryRunUseCase
import app.lusk.virga.sync.SyncProgressMonitor
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val dryRunUseCase: DryRunUseCase,
    private val checkUseCase: CheckUseCase,
) : ViewModel() {

    private val taskId = MutableStateFlow<Long?>(null)

    /** Dry-run preview state (WS2.3). */
    data class DryRunUiState(val running: Boolean = false, val result: DryRunResult? = null)

    private val _dryRun = MutableStateFlow(DryRunUiState())
    val dryRun: StateFlow<DryRunUiState> = _dryRun.asStateFlow()

    /** True when a dry-run preview can be offered for the current task (not SAF). */
    fun previewAvailable(): Boolean =
        uiState.value.task?.let { dryRunUseCase.isAvailableFor(it) } ?: false

    fun previewChanges() {
        val task = uiState.value.task ?: return
        if (!dryRunUseCase.isAvailableFor(task)) return
        // Set running=true INSIDE the launch: if the scope is already cancelled
        // (VM cleared mid-tap), nothing is published, so dryRun can't get stuck
        // at running=true with no coroutine left to clear it.
        viewModelScope.launch {
            _dryRun.value = DryRunUiState(running = true)
            _dryRun.value = DryRunUiState(running = false, result = dryRunUseCase.preview(task))
        }
    }

    fun dismissPreview() { _dryRun.value = DryRunUiState() }

    /** Check (verify) state — mirrors [DryRunUiState] for symmetry. */
    data class CheckUiState(val running: Boolean = false, val result: CheckResult? = null)

    private val _checkState = MutableStateFlow(CheckUiState())
    val checkState: StateFlow<CheckUiState> = _checkState.asStateFlow()

    /** True when a verify (check) operation can be offered for the current task. */
    fun verifyAvailable(): Boolean =
        uiState.value.task?.let { checkUseCase.isAvailableFor(it) } ?: false

    fun verifyChanges() {
        val task = uiState.value.task ?: return
        if (!checkUseCase.isAvailableFor(task)) return
        viewModelScope.launch {
            _checkState.value = CheckUiState(running = true)
            _checkState.value = CheckUiState(running = false, result = checkUseCase.verify(task))
        }
    }

    fun dismissVerify() { _checkState.value = CheckUiState() }

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
