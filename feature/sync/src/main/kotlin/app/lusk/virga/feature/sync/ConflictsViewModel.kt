package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.error.toUserMessage
import app.lusk.virga.core.data.ConflictChoice
import app.lusk.virga.core.data.ConflictRepository
import app.lusk.virga.core.common.model.Conflict
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConflictsUiState(
    val conflicts: List<Conflict> = emptyList(),
    val resolvingId: Long? = null,
    val error: String? = null,
    val selectedIds: Set<Long> = emptySet(),
    val pendingBulkChoice: ConflictChoice? = null,
)

@HiltViewModel
class ConflictsViewModel @Inject constructor(
    private val repository: ConflictRepository,
) : ViewModel() {

    private val transient = MutableStateFlow<Pair<Long?, String?>>(null to null)
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _pendingBulkChoice = MutableStateFlow<ConflictChoice?>(null)

    // Selection snapshot captured when the bulk dialog opens, so confirming acts
    // strictly on what was selected — never silently on "all" if the live
    // selection changes while the dialog is open.
    private var pendingBulkIds: Set<Long> = emptySet()

    val uiState: StateFlow<ConflictsUiState> =
        combine(
            repository.unresolved,
            transient,
            _selectedIds,
            _pendingBulkChoice,
        ) { conflicts, (resolvingId, error), selectedIds, pendingBulk ->
            ConflictsUiState(
                conflicts = conflicts,
                resolvingId = resolvingId,
                error = error,
                selectedIds = selectedIds,
                pendingBulkChoice = pendingBulk,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConflictsUiState())

    fun resolve(conflict: Conflict, choice: ConflictChoice) = viewModelScope.launch {
        transient.value = conflict.id to null
        val result = repository.resolve(conflict, choice)
        transient.value = null to result.exceptionOrNull()?.toUserMessage()
    }

    fun clearError() {
        transient.value = transient.value.first to null
    }

    // ----- Selection -----

    fun toggleSelection(conflictId: Long) = _selectedIds.update { ids ->
        if (conflictId in ids) ids - conflictId else ids + conflictId
    }

    fun clearSelection() = _selectedIds.update { emptySet() }

    /** Opens the bulk-confirm dialog, snapshotting the current selection. No-op
     *  when nothing is selected (the UI only exposes this in selection mode). */
    fun requestBulkChoice(choice: ConflictChoice) {
        val snapshot = _selectedIds.value
        if (snapshot.isEmpty()) return
        pendingBulkIds = snapshot
        _pendingBulkChoice.value = choice
    }

    fun cancelBulkChoice() {
        _pendingBulkChoice.value = null
        pendingBulkIds = emptySet()
    }

    fun confirmBulkChoice() = viewModelScope.launch {
        val choice = _pendingBulkChoice.value ?: return@launch
        val ids = pendingBulkIds
        _pendingBulkChoice.value = null
        pendingBulkIds = emptySet()
        if (ids.isEmpty()) return@launch
        // Resolve strictly the snapshot — never fall back to "all".
        val conflicts = uiState.value.conflicts.filter { it.id in ids }
        var firstError: String? = null
        conflicts.forEach { conflict ->
            val result = repository.resolve(conflict, choice)
            if (result.isFailure && firstError == null) {
                firstError = result.exceptionOrNull()?.toUserMessage()
            }
        }
        _selectedIds.value = emptySet()
        if (firstError != null) transient.value = null to firstError
    }
}

