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
import kotlinx.coroutines.CancellationException
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
    val path: String = "",
    val rawEntries: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val truncated: Boolean = false,
    val sortConfig: SortConfig = SortConfig(),
    val searchQuery: String = "",
    val searchActive: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val showCreateFolderDialog: Boolean = false,
    @param:StringRes val createFolderError: Int? = null,
    val creatingFolder: Boolean = false,
    val statusMessage: String? = null,
    val fileOpInProgress: Boolean = false,
    val showDeleteConfirmDialog: Boolean = false,
    val showRenameDialog: Boolean = false,
    val renamePath: String? = null,
    @param:StringRes val renameError: Int? = null,
    val showMoveDialog: Boolean = false,
    val showCopyDialog: Boolean = false,
    val propertiesItem: FileItem? = null,
    val actionSheetItem: FileItem? = null,
    val transferInProgress: Boolean = false,
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

    /** Collision check for rename — excludes the item being renamed from the sibling list. */
    @StringRes
    private fun checkRenameCollision(name: String, excludePath: String): Int? {
        val exists = _state.value.rawEntries.any {
            it.name.equals(name, ignoreCase = true) && it.path != excludePath
        }
        return if (exists) R.string.explorer_new_folder_exists else null
    }

    /** Structural + collision validation for rename. Returns a string-res error or null when valid. */
    @StringRes
    private fun renameValidationError(trimmed: String, excludePath: String): Int? {
        if (!isStructurallyValidFolderName(trimmed)) return R.string.explorer_rename_invalid_name
        return checkRenameCollision(trimmed, excludePath)
    }

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
                if (e is CancellationException) throw e
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

    /** Re-lists the current directory without showing the full-screen spinner. */
    fun refresh() {
        val remote = _state.value.remoteName ?: return
        load(remote, _state.value.path, isRefresh = true)
    }

    /** Opens the properties sheet for [item]. */
    fun showProperties(item: FileItem) {
        _state.update { it.copy(propertiesItem = item) }
    }

    /** Closes the properties sheet. */
    fun dismissProperties() {
        _state.update { it.copy(propertiesItem = null) }
    }

    fun showActionSheet(item: FileItem) { _state.update { it.copy(actionSheetItem = item) } }
    fun dismissActionSheet() { _state.update { it.copy(actionSheetItem = null) } }

    fun downloadForAction(item: FileItem, cacheDir: java.io.File, onReady: (java.io.File) -> Unit) {
        val remote = _state.value.remoteName ?: return
        if (_state.value.transferInProgress) return
        viewModelScope.launch {
            _state.update { it.copy(transferInProgress = true, actionSheetItem = null) }
            runTransfer(
                io = { fileBrowser.downloadToCache(remote, item.path, java.io.File(cacheDir, "shared")) },
                onSuccess = { file -> _state.update { it.copy(transferInProgress = false) }; onReady(file) },
                onFailure = { e ->
                    _state.update { it.copy(transferInProgress = false, statusMessage = e.toUserMessage()) }
                },
            )
        }
    }

    fun uploadLocalFile(localFile: java.io.File) {
        val remote = _state.value.remoteName ?: return
        val currentPath = _state.value.path
        if (_state.value.transferInProgress) return
        val destPath = if (currentPath.isEmpty()) localFile.name else "$currentPath/${localFile.name}"
        viewModelScope.launch {
            _state.update { it.copy(transferInProgress = true) }
            runTransfer(
                io = { fileBrowser.uploadFromLocal(localFile, remote, destPath) },
                onSuccess = { _state.update { it.copy(transferInProgress = false) }; load(remote, currentPath) },
                onFailure = { e ->
                    _state.update { it.copy(transferInProgress = false, statusMessage = e.toUserMessage()) }
                },
            )
        }
    }

    private suspend fun <T> runTransfer(
        io: suspend () -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val result = runCatching { withContext(dispatchers.io) { io() } }
        result.fold(
            onSuccess = { onSuccess(it) },
            // Safe-cast: runCatching also traps Error subclasses (e.g. OutOfMemoryError); a
            // forced `as Exception` would throw ClassCastException and mask the original.
            onFailure = { e ->
                if (e is CancellationException) throw e else onFailure(e as? Exception ?: RuntimeException(e))
            },
        )
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

    /** Dismisses any one-shot status message after the UI has consumed it. */
    fun clearStatusMessage() {
        _state.update { it.copy(statusMessage = null) }
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    fun openDeleteConfirmDialog() {
        if (_state.value.selectedPaths.isNotEmpty()) {
            _state.update { it.copy(showDeleteConfirmDialog = true) }
        }
    }

    fun dismissDeleteConfirmDialog() {
        _state.update { it.copy(showDeleteConfirmDialog = false) }
    }

    /** Deletes selected items per-item (files→deleteFile, dirs→purge). Always refreshes afterward. */
    fun deleteSelected() {
        val rawEntries = _state.value.rawEntries
        runBatchFileOp(hideDialog = { copy(showDeleteConfirmDialog = false) }) { path ->
            if (rawEntries.find { it.path == path }?.isDir == true) fileBrowser.purge(remote, path)
            else fileBrowser.deleteFile(remote, path)
        }
    }

    // -------------------------------------------------------------------------
    // Rename
    // -------------------------------------------------------------------------

    fun openRenameDialog(path: String) {
        _state.update { it.copy(showRenameDialog = true, renamePath = path) }
    }

    fun dismissRenameDialog() {
        _state.update { it.copy(showRenameDialog = false, renamePath = null, renameError = null) }
    }

    /** Renames [path] to [newName] in the same parent dir. Validates structure + collision (self excluded). */
    fun rename(path: String, newName: String) {
        val errorRes = renameValidationError(newName.trim(), excludePath = path)
        if (errorRes != null) { _state.update { it.copy(renameError = errorRes) }; return }
        val remote = _state.value.remoteName ?: return
        if (_state.value.fileOpInProgress) return
        val trimmed = newName.trim()
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        val destPath = if (parent.isEmpty()) trimmed else "$parent/$trimmed"

        viewModelScope.launch {
            _state.update {
                it.copy(fileOpInProgress = true, showRenameDialog = false, renamePath = null, renameError = null)
            }
            var message: String? = null
            try {
                withContext(dispatchers.io) { fileBrowser.moveFile(remote, path, destPath) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.toUserMessage()
            }
            _state.update { it.copy(fileOpInProgress = false, statusMessage = message) }
            if (message == null) { clearSelection(); load(remote, _state.value.path) }
        }
    }

    // -------------------------------------------------------------------------
    // Move / Copy
    // -------------------------------------------------------------------------

    fun openMoveDialog() {
        if (_state.value.selectedPaths.isNotEmpty()) _state.update { it.copy(showMoveDialog = true) }
    }

    fun dismissMoveDialog() { _state.update { it.copy(showMoveDialog = false) } }

    fun openCopyDialog() {
        if (_state.value.selectedPaths.isNotEmpty()) _state.update { it.copy(showCopyDialog = true) }
    }

    fun dismissCopyDialog() { _state.update { it.copy(showCopyDialog = false) } }

    /** Moves selected items into [destDir] per-item. Slashes normalized; empty = root. Always refreshes. */
    fun moveSelected(destDir: String) {
        val rawEntries = _state.value.rawEntries
        val normalizedDest = destDir.trim().trim('/')
        runBatchFileOp(hideDialog = { copy(showMoveDialog = false) }) { path ->
            val name = rawEntries.find { it.path == path }?.name ?: path.substringAfterLast('/')
            val destPath = if (normalizedDest.isEmpty()) name else "$normalizedDest/$name"
            fileBrowser.moveFile(remote, path, destPath)
        }
    }

    /** Copies selected items into [destDir] per-item. Slashes normalized; empty = root. Always refreshes. */
    fun copySelected(destDir: String) {
        val rawEntries = _state.value.rawEntries
        val normalizedDest = destDir.trim().trim('/')
        runBatchFileOp(hideDialog = { copy(showCopyDialog = false) }) { path ->
            val name = rawEntries.find { it.path == path }?.name ?: path.substringAfterLast('/')
            val destPath = if (normalizedDest.isEmpty()) name else "$normalizedDest/$name"
            fileBrowser.copyFile(remote, path, destPath)
        }
    }

    /** Shared batch-op skeleton: guards, per-item loop (CE rethrown), always refreshes. */
    private fun runBatchFileOp(
        hideDialog: FileBrowserUiState.() -> FileBrowserUiState,
        perItem: suspend BatchScope.(path: String) -> Unit,
    ) {
        if (_state.value.fileOpInProgress) return
        val remote = _state.value.remoteName ?: return
        val paths = _state.value.selectedPaths
        if (paths.isEmpty()) return
        val scope = BatchScope(remote)

        viewModelScope.launch {
            _state.update { hideDialog(it).copy(fileOpInProgress = true) }
            val failed = mutableListOf<String>()
            withContext(dispatchers.io) {
                for (path in paths) {
                    try {
                        scope.perItem(path)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed.add(path)
                    }
                }
            }
            _state.update { it.copy(fileOpInProgress = false, statusMessage = buildPartialFailureMessage(paths.size - failed.size, failed.size)) }
            clearSelection()
            load(remote, _state.value.path)
        }
    }

    private inner class BatchScope(val remote: String)

    private var loadJob: kotlinx.coroutines.Job? = null

    private fun load(remote: String, path: String, isRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(remoteName = remote, path = path, loading = !isRefresh, isRefreshing = isRefresh, error = null)
            }
            try {
                val raw = fileBrowser.list(remote, path)
                val (capped, truncated) = withContext(dispatchers.default) {
                    val truncated = raw.size > MAX_ENTRIES
                    (if (truncated) raw.take(MAX_ENTRIES) else raw) to truncated
                }
                _state.update { it.copy(rawEntries = capped, loading = false, isRefreshing = false, truncated = truncated) }
            } catch (e: VirgaError) {
                _state.update { it.copy(loading = false, isRefreshing = false, error = e.toUserMessage()) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, isRefreshing = false, error = e.toUserMessage()) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        CoroutineScope(dispatchers.io + SupervisorJob()).launch { runCatching { fileBrowser.releaseDaemon() } }
    }

    private fun buildPartialFailureMessage(succeeded: Int, failCount: Int): String? {
        if (failCount == 0) return null
        return "Succeeded: $succeeded, failed: $failCount"
    }

    private companion object {
        const val MAX_ENTRIES = 2_000
    }
}
