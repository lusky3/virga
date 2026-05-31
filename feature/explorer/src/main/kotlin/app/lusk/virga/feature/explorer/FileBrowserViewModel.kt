package app.lusk.virga.feature.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.error.toUserMessage
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.rclone.RcloneEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private fun buildComparator(config: SortConfig): Comparator<FileItem> {
    val fieldComparator: Comparator<FileItem> = when (config.field) {
        SortField.NAME -> compareBy { it.name.lowercase() }
        SortField.SIZE -> compareBy { it.size }
        SortField.MODIFIED -> compareBy { it.modTimeEpochMs ?: 0L }
    }
    val orderedField = if (config.order == SortOrder.DESC) fieldComparator.reversed() else fieldComparator
    return compareByDescending<FileItem> { it.isDir }.then(orderedField)
}

enum class SortField { NAME, SIZE, MODIFIED }
enum class SortOrder { ASC, DESC }

data class SortConfig(
    val field: SortField = SortField.NAME,
    val order: SortOrder = SortOrder.ASC,
)

data class FileBrowserUiState(
    val remoteName: String? = null,
    /** Current path within the remote, "" for root. */
    val path: String = "",
    val rawEntries: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** True when the directory has more entries than [MAX_ENTRIES]. */
    val truncated: Boolean = false,
    val sortConfig: SortConfig = SortConfig(),
    val searchQuery: String = "",
    val searchActive: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
) {
    val atRoot: Boolean get() = path.isEmpty()
    val breadcrumb: List<String> get() = path.split('/').filter { it.isNotBlank() }
}

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val engine: RcloneEngine,
    remoteRepository: RemoteRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(FileBrowserUiState())
    val state: StateFlow<FileBrowserUiState> = _state.asStateFlow()

    /** Precomputed, sorted+filtered list — safe to read on every recomposition. */
    val entries: StateFlow<List<FileItem>> = _state
        .map { s ->
            val filtered = if (s.searchQuery.isBlank()) s.rawEntries
            else s.rawEntries.filter { it.name.contains(s.searchQuery, ignoreCase = true) }
            filtered.sortedWith(buildComparator(s.sortConfig))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val remotes: StateFlow<List<String>> = remoteRepository.remotes
        .map { list -> list.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Selects [name] only when no remote is currently selected (used by initialRemote). */
    fun selectRemoteIfUnset(name: String) {
        if (_state.value.remoteName == null) selectRemote(name)
    }

    fun selectRemote(name: String) {
        _state.value = FileBrowserUiState(remoteName = name, path = "")
        load(name, "")
    }

    fun open(entry: FileItem) {
        if (!entry.isDir) return
        val remote = _state.value.remoteName ?: return
        _state.update { it.copy(searchQuery = "", searchActive = false, selectionMode = false, selectedPaths = emptySet()) }
        load(remote, entry.path)
    }

    /** Navigates up one directory; no-op at root. */
    fun up() {
        val remote = _state.value.remoteName ?: return
        val current = _state.value.path
        if (current.isEmpty()) return
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
        _state.update { it.copy(searchQuery = "", searchActive = false, selectionMode = false, selectedPaths = emptySet()) }
        load(remote, parent)
    }

    fun retry() {
        val remote = _state.value.remoteName ?: return
        load(remote, _state.value.path)
    }

    fun setSortConfig(config: SortConfig) {
        _state.update { it.copy(sortConfig = config) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun toggleSearch() {
        _state.update {
            if (it.searchActive) it.copy(searchActive = false, searchQuery = "")
            else it.copy(searchActive = true)
        }
    }

    fun toggleSelectionMode() {
        _state.update { it.copy(selectionMode = !it.selectionMode, selectedPaths = emptySet()) }
    }

    fun toggleSelection(path: String) {
        _state.update { current ->
            val updated = if (path in current.selectedPaths)
                current.selectedPaths - path
            else
                current.selectedPaths + path
            current.copy(selectedPaths = updated, selectionMode = updated.isNotEmpty())
        }
    }

    fun enterSelectionMode(path: String) {
        _state.update { it.copy(selectionMode = true, selectedPaths = setOf(path)) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectionMode = false, selectedPaths = emptySet()) }
    }

    private fun load(remote: String, path: String) {
        viewModelScope.launch {
            _state.update { it.copy(remoteName = remote, path = path, loading = true, error = null) }
            try {
                val raw = engine.listDir("$remote:", path)
                val (capped, truncated) = withContext(dispatchers.default) {
                    val truncated = raw.size > MAX_ENTRIES
                    (if (truncated) raw.take(MAX_ENTRIES) else raw) to truncated
                }
                _state.update { it.copy(rawEntries = capped, loading = false, truncated = truncated) }
            } catch (e: VirgaError) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            } catch (e: Exception) {
                // Navigating away cancels this load; a CancellationException is
                // normal flow control, not an error to surface to the user.
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { runCatching { engine.stopDaemon() } }
    }

    private companion object {
        const val MAX_ENTRIES = 2_000
    }
}
