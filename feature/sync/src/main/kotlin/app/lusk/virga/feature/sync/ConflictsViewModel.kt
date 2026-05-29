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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConflictsUiState(
    val conflicts: List<ConflictEntity> = emptyList(),
    val resolvingId: Long? = null,
    val error: String? = null,
)

@HiltViewModel
class ConflictsViewModel @Inject constructor(
    private val repository: ConflictRepository,
) : ViewModel() {

    private val transient = MutableStateFlow<Pair<Long?, String?>>(null to null)

    val uiState: StateFlow<ConflictsUiState> =
        combine(repository.unresolved, transient) { conflicts, (resolvingId, error) ->
            ConflictsUiState(conflicts = conflicts, resolvingId = resolvingId, error = error)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConflictsUiState())

    fun resolve(conflict: ConflictEntity, choice: ConflictChoice) = viewModelScope.launch {
        transient.value = conflict.id to null
        val result = repository.resolve(conflict, choice)
        transient.value = null to result.exceptionOrNull()?.toUserMessage()
    }

    fun clearError() {
        transient.value = transient.value.first to null
    }
}
