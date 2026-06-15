package app.lusk.virga.feature.explorer

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.error.toUserMessage
import app.lusk.virga.core.common.validation.isValidFolderName
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.data.FileBrowserRepository
import app.lusk.virga.core.data.RemoteFolderPickStore
import app.lusk.virga.core.data.RemoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
    /** True while the "create folder" dialog is shown (pick mode). */
    val showCreateFolderDialog: Boolean = false,
    /** String resource for the current create-folder error, or null if none. */
    @param:StringRes val createFolderError: Int? = null,
    /** True while a folder-creation RC call is in flight. */
    val creatingFolder: Boolean = false,
) {
    val atRoot: Boolean get() = path.isEmpty()
    val breadcrumb: List<String> get() = path.split('/').filter { it.isNotBlank() }
}

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val fileBrowser: FileBrowserRepository,
    remoteRepository: RemoteRepository,
    private val dispatchers: DispatcherProvider,
    private val folderPickStore: RemoteFolderPickStore,
) : ViewModel() {

    /** Records the current folder as the chosen destination (pick mode). */
    fun pickFolder(remoteName: String, path: String) = folderPickStore.pick(remoteName, path)

    /** Shows the create-folder dialog (pick mode), clearing any prior error. */
    fun openCreateFolderDialog() {
        _state.update { it.copy(showCreateFolderDialog = true, createFolderError = null) }
    }

    /** Dismisses the create-folder dialog and clears its error/in-flight state. */
    fun dismissCreateFolderDialog() {
        // Cancel any in-flight mkdir so dismissing can't later navigate into a folder
        // the user backed out of.
        createFolderJob?.cancel()
        _state.update {
            it.copy(showCreateFolderDialog = false, createFolderError = null, creatingFolder = false)
        }
    }

    /**
     * Validates a proposed folder [name] against the current directory's entries.
     * Returns a string resource for the failure reason, or null when valid.
     */
    @StringRes
    private fun validateFolderName(name: String): Int? {
        val trimmed = name.trim()
        if (!isStructurallyValidFolderName(trimmed)) return R.string.explorer_new_folder_invalid_name
        val exists = _state.value.rawEntries.any { it.name.equals(trimmed, ignoreCase = true) }
        return if (exists) R.string.explorer_new_folder_exists else null
    }

    /** Name is non-empty, not `.`/`..`, within length, and free of separators/control chars. */
    private fun isStructurallyValidFolderName(trimmed: String): Boolean = isValidFolderName(trimmed)

    /** L3: the in-flight folder creation, cancelled on dismiss/relaunch. */
    private var createFolderJob: kotlinx.coroutines.Job? = null

    /**
     * Validates [name], creates it under the current path, then navigates into the
     * new folder so the "Select folder" FAB picks it. Surfaces validation/RC errors
     * via [FileBrowserUiState.createFolderError].
     */
    fun createFolder(name: String) {
        val remote = _state.value.remoteName ?: return
        val validationError = validateFolderName(name)
        if (validationError != null) {
            _state.update { it.copy(createFolderError = validationError) }
            return
        }
        val trimmed = name.trim()
        val basePath = _state.value.path
        val childPath = if (basePath.isEmpty()) trimmed else "$basePath/$trimmed"

        createFolderJob?.cancel()
        createFolderJob = viewModelScope.launch {
            _state.update { it.copy(creatingFolder = true, createFolderError = null) }
            try {
                withContext(dispatchers.io) { fileBrowser.mkdir(remote, childPath) }
                _state.update {
                    it.copy(showCreateFolderDialog = false, creatingFolder = false, createFolderError = null)
                }
                load(remote, childPath)
            } catch (e: VirgaError) {
                _state.update { it.copy(creatingFolder = false, createFolderError = R.string.explorer_new_folder_failed) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(creatingFolder = false, createFolderError = R.string.explorer_new_folder_failed) }
            }
        }
    }

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

    /** L3: the in-flight load, cancelled before each relaunch so a slow list(A)
     *  finishing after list(parent) can't overwrite the parent's entries. */
    private var loadJob: kotlinx.coroutines.Job? = null

    private fun load(remote: String, path: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(remoteName = remote, path = path, loading = true, error = null) }
            try {
                val raw = fileBrowser.list(remote, path)
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
        // viewModelScope is already cancelled by super.onCleared(), so a
        // viewModelScope.launch here never runs — the rclone daemon would leak.
        // Release it on a detached scope so cleanup actually executes.
        CoroutineScope(dispatchers.io + SupervisorJob()).launch {
            runCatching { fileBrowser.releaseDaemon() }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 2_000
    }
}
