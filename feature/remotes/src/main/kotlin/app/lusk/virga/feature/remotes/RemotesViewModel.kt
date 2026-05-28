package app.lusk.virga.feature.remotes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.database.entity.RemoteEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RemotesUiState(
    val remotes: List<RemoteEntity> = emptyList(),
    val refreshing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class RemotesViewModel @Inject constructor(
    private val repository: RemoteRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    val uiState: StateFlow<RemotesUiState> = combineState()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            transient.value = transient.value.copy(refreshing = true)
            val result = repository.refresh()
            transient.value = TransientState(
                refreshing = false,
                message = result.exceptionOrNull()?.message,
            )
        }
    }

    fun deleteRemote(name: String) {
        viewModelScope.launch {
            val result = repository.deleteRemote(name)
            transient.value = transient.value.copy(message = result.exceptionOrNull()?.message)
        }
    }

    /** Creates a remote from a manual config. [paramsText] is "key=value" per line. */
    fun addRemote(name: String, type: String, paramsText: String, onDone: () -> Unit) {
        val params = paramsText.lines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
        viewModelScope.launch {
            val result = repository.addRemote(name.trim(), type.trim(), params)
            transient.value = transient.value.copy(message = result.exceptionOrNull()?.message)
            if (result.isSuccess) onDone()
        }
    }

    /** Imports an existing rclone.conf (e.g. exported from desktop). */
    fun importConfig(confText: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = repository.importConfig(confText)
            transient.value = transient.value.copy(message = result.exceptionOrNull()?.message)
            if (result.isSuccess) onDone()
        }
    }

    fun clearMessage() {
        transient.value = transient.value.copy(message = null)
    }

    private data class TransientState(val refreshing: Boolean = false, val message: String? = null)

    private fun combineState(): StateFlow<RemotesUiState> =
        combine(repository.remotes, transient) { remotes, t ->
            RemotesUiState(remotes = remotes, refreshing = t.refreshing, message = t.message)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemotesUiState())
}
