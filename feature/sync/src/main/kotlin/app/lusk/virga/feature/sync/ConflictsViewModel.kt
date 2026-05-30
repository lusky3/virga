package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.error.toUserMessage
import app.lusk.virga.core.data.ConflictChoice
import app.lusk.virga.core.data.ConflictRepository
import app.lusk.virga.core.database.entity.ConflictEntity
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
    val conflicts: List<ConflictEntity> = emptyList(),
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

    fun resolve(conflict: ConflictEntity, choice: ConflictChoice) = viewModelScope.launch {
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

    fun requestBulkChoice(choice: ConflictChoice) { _pendingBulkChoice.value = choice }

    fun cancelBulkChoice() { _pendingBulkChoice.value = null }

    fun confirmBulkChoice() = viewModelScope.launch {
        val choice = _pendingBulkChoice.value ?: return@launch
        val ids = _selectedIds.value.ifEmpty {
            uiState.value.conflicts.mapTo(mutableSetOf()) { it.id }
        }
        val conflicts = uiState.value.conflicts.filter { it.id in ids }
        conflicts.forEach { conflict ->
            repository.resolve(conflict, choice)
        }
        _selectedIds.value = emptySet()
        _pendingBulkChoice.value = null
    }
}

