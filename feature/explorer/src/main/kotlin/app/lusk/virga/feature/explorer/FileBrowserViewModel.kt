package app.lusk.virga.feature.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.error.toUserMessage
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.rclone.RcloneEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class FileBrowserUiState(
    val remoteName: String? = null,
    /** Current path within the remote, "" for root. */
    val path: String = "",
    val entries: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** True when the directory has more entries than [MAX_ENTRIES] and the list is truncated. */
    val truncated: Boolean = false,
) {
    val atRoot: Boolean get() = path.isEmpty()
    val breadcrumb: List<String> get() = path.split('/').filter { it.isNotBlank() }
}

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val engine: RcloneEngine,
    remoteRepository: RemoteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FileBrowserUiState())
    val state: StateFlow<FileBrowserUiState> = _state.asStateFlow()

    val remotes: StateFlow<List<String>> = remoteRepository.remotes
        .map { list -> list.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectRemote(name: String) {
        _state.value = FileBrowserUiState(remoteName = name, path = "")
        load(name, "")
    }

    fun open(entry: FileItem) {
        if (!entry.isDir) return
        val remote = _state.value.remoteName ?: return
        load(remote, entry.path)
    }

    /** Navigates up one directory; no-op at root. */
    fun up() {
        val remote = _state.value.remoteName ?: return
        val current = _state.value.path
        if (current.isEmpty()) return
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
        load(remote, parent)
    }

    fun retry() {
        val remote = _state.value.remoteName ?: return
        load(remote, _state.value.path)
    }

    private fun load(remote: String, path: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                remoteName = remote, path = path, loading = true, error = null,
            )
            try {
                val raw = engine.listDir("$remote:", path)
                val (sorted, truncated) = withContext(Dispatchers.Default) {
                    val s = raw.sortedWith(
                        compareByDescending<FileItem> { it.isDir }.thenBy { it.name.lowercase() },
                    )
                    val truncated = s.size > MAX_ENTRIES
                    (if (truncated) s.take(MAX_ENTRIES) else s) to truncated
                }
                _state.value = _state.value.copy(entries = sorted, loading = false, truncated = truncated)
            } catch (e: VirgaError) {
                _state.value = _state.value.copy(loading = false, error = e.toUserMessage())
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.toUserMessage(),
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { runCatching { engine.stopDaemon() } }
    }

    private companion object {
        /** Cap displayed entries per directory to avoid unbounded list allocation on the main thread. */
        const val MAX_ENTRIES = 2_000
    }
}
