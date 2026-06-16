package app.lusk.virga.feature.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.designsystem.component.EmptyState
import app.lusk.virga.core.designsystem.component.rememberLongPressHaptic
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Thread-safe, locale-stable formatter for modified dates. */
private val MOD_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
        .withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onBack: () -> Unit,
    onNavigateToRemotes: () -> Unit = {},
    onSyncFolder: (remote: String, path: String) -> Unit = { _, _ -> },
    /** When true, the browser acts as a destination-folder picker: the action
     *  records the current folder (via the ViewModel) and returns instead of
     *  creating a sync task. */
    pickMode: Boolean = false,
    initialRemote: String? = null,
    viewModel: FileBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val remotes by viewModel.remotes.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    if (initialRemote != null) {
        LaunchedEffect(initialRemote) { viewModel.selectRemoteIfUnset(initialRemote) }
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage
        if (msg != null) {
            snackbar.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = state.remoteName != null && !state.atRoot && !state.selectionMode) {
        viewModel.up()
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val selectionActions = SelectionActions(
        onDelete = viewModel::openDeleteConfirmDialog,
        onRename = {
            val path = state.selectedPaths.singleOrNull() ?: return@SelectionActions
            viewModel.openRenameDialog(path)
        },
        onMove = viewModel::openMoveDialog,
        onCopy = viewModel::openCopyDialog,
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FileBrowserTopBar(
                state = state,
                scrollBehavior = scrollBehavior,
                onBack = { if (!state.atRoot) viewModel.up() else onBack() },
                onSearchQuery = viewModel::setSearchQuery,
                onToggleSearch = viewModel::toggleSearch,
                onSort = viewModel::setSortConfig,
                onClearSelection = viewModel::clearSelection,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            val remote = state.remoteName
            if (remote != null && !state.loading && state.error == null) {
                // Selected folders (files can't be a sync source); drives the
                // "Create sync task from selection" action (WS2.6).
                val selectedFolders = entries.filter { it.path in state.selectedPaths && it.isDir }
                when {
                    state.selectionMode && !pickMode -> {
                        if (selectedFolders.isNotEmpty()) {
                            val label = stringResource(R.string.explorer_create_task_from_selection)
                            ExtendedFloatingActionButton(
                                onClick = {
                                    // The prefill editor takes one source folder; use the first
                                    // selected. (Multi-folder fan-out is a future enhancement.)
                                    onSyncFolder(remote, selectedFolders.first().path)
                                    viewModel.clearSelection()
                                },
                                icon = { Icon(Icons.Filled.CloudSync, contentDescription = null) },
                                text = { Text(label) },
                            )
                        }
                    }
                    pickMode -> {
                        val pickLabel = stringResource(R.string.explorer_select_folder)
                        val newFolderDesc = stringResource(R.string.explorer_cd_new_folder)
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
                        ) {
                            FloatingActionButton(
                                onClick = viewModel::openCreateFolderDialog,
                                modifier = Modifier.semantics { contentDescription = newFolderDesc },
                            ) {
                                Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                            }
                            ExtendedFloatingActionButton(
                                onClick = { viewModel.pickFolder(remote, state.path); onBack() },
                                icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                                text = { Text(pickLabel) },
                            )
                        }
                    }
                    else -> {
                        val syncLabel = stringResource(R.string.explorer_sync_folder)
                        ExtendedFloatingActionButton(
                            onClick = { onSyncFolder(remote, state.path) },
                            icon = { Icon(Icons.Filled.CloudSync, contentDescription = null) },
                            text = { Text(syncLabel) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.remoteName == null -> RemotePicker(
                    remotes = remotes,
                    onSelect = viewModel::selectRemote,
                    onNavigateToRemotes = onNavigateToRemotes,
                )
                state.loading -> {
                    val loadingDesc = stringResource(R.string.explorer_cd_loading)
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .semantics { contentDescription = loadingDesc },
                    )
                }
                state.error != null -> ErrorState(state.error!!, viewModel::retry)
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.selectionMode) {
                        SelectionActionBar(
                            selectionCount = state.selectedPaths.size,
                            actions = selectionActions,
                        )
                    }
                    if (state.truncated) {
                        val truncatedDesc = stringResource(R.string.explorer_truncated_notice)
                        Text(
                            truncatedDesc,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(horizontal = VirgaSpacing.md, vertical = VirgaSpacing.xs)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    if (entries.isEmpty()) {
                        EmptyFolder()
                    } else {
                        FileList(
                            entries = entries,
                            selectedPaths = state.selectedPaths,
                            selectionMode = state.selectionMode,
                            onOpen = viewModel::open,
                            onLongPress = viewModel::enterSelectionMode,
                            onToggleSelect = viewModel::toggleSelection,
                        )
                    }
                }
            }
        }
    }

    if (pickMode && state.showCreateFolderDialog) {
        CreateFolderDialog(
            errorRes = state.createFolderError,
            creating = state.creatingFolder,
            onDismiss = viewModel::dismissCreateFolderDialog,
            onConfirm = viewModel::createFolder,
        )
    }

    if (state.showDeleteConfirmDialog) {
        DeleteConfirmDialog(
            count = state.selectedPaths.size,
            onConfirm = viewModel::deleteSelected,
            onDismiss = viewModel::dismissDeleteConfirmDialog,
        )
    }

    if (state.showRenameDialog) {
        val path = state.renamePath ?: ""
        RenameDialog(
            initialName = path.substringAfterLast('/').ifEmpty { path },
            errorRes = state.renameError,
            onConfirm = { viewModel.rename(path, it) },
            onDismiss = viewModel::dismissRenameDialog,
        )
    }

    if (state.showMoveDialog) {
        DestinationDialog(
            titleRes = R.string.explorer_dest_title_move,
            confirmRes = R.string.explorer_dest_confirm_move,
            onConfirm = viewModel::moveSelected,
            onDismiss = viewModel::dismissMoveDialog,
        )
    }

    if (state.showCopyDialog) {
        DestinationDialog(
            titleRes = R.string.explorer_dest_title_copy,
            confirmRes = R.string.explorer_dest_confirm_copy,
            onConfirm = viewModel::copySelected,
            onDismiss = viewModel::dismissCopyDialog,
        )
    }
}

@Composable
private fun CreateFolderDialog(
    errorRes: Int?,
    creating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val confirmEnabled = name.isNotBlank() && !creating
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_new_folder_title)) },
        text = {
            // Error goes in the field's supportingText slot (not a sibling Text) so
            // it's announced with the field by TalkBack and styled via isError.
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                enabled = !creating,
                isError = errorRes != null,
                label = { Text(stringResource(R.string.explorer_new_folder_hint)) },
                supportingText = if (errorRes != null) {
                    { Text(stringResource(errorRes)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (confirmEnabled) onConfirm(name) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = confirmEnabled) {
                Text(stringResource(R.string.explorer_new_folder_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileBrowserTopBar(
    state: FileBrowserUiState,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onSort: (SortConfig) -> Unit,
    onClearSelection: () -> Unit,
) {
    TopAppBar(
        title = {
            FileBrowserTitle(
                state = state,
                onSearchQuery = onSearchQuery,
            )
        },
        navigationIcon = {
            IconButton(onClick = if (state.selectionMode) onClearSelection else onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.explorer_cd_back),
                )
            }
        },
        actions = {
            FileBrowserActions(
                state = state,
                onToggleSearch = onToggleSearch,
                onSort = onSort,
            )
        },
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(
    entries: List<FileItem>,
    selectedPaths: Set<String>,
    selectionMode: Boolean,
    onOpen: (FileItem) -> Unit,
    onLongPress: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
) {
    val longPressHaptic = rememberLongPressHaptic()
    LazyColumn(Modifier.fillMaxSize()) {
        // Drive (and other backends) can hold multiple files with the SAME name
        // in one folder, so path alone isn't a unique LazyColumn key — collisions
        // crash with "Key … was already used". Disambiguate with the index.
        itemsIndexed(entries, key = { index, item -> "${item.path} $index" }) { _, item ->
            val isSelected = item.path in selectedPaths
            val openFolderLabel = stringResource(R.string.explorer_cd_open_folder)
            val a11yDesc = buildItemDescription(item)

            ListItem(
                headlineContent = {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = { ItemSupportingText(item) },
                leadingContent = {
                    if (selectionMode) {
                        Icon(
                            if (isSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                            contentDescription = null,
                        )
                    } else {
                        Icon(
                            if (item.isDir) Icons.Filled.Folder
                            else Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier
                    .combinedClickable(
                        enabled = item.isDir || selectionMode,
                        onClick = {
                            if (selectionMode) onToggleSelect(item.path)
                            else onOpen(item)
                        },
                        onLongClick = { longPressHaptic(); onLongPress(item.path) },
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = a11yDesc
                        if (item.isDir) {
                            role = Role.Button
                            onClick(label = openFolderLabel) { onOpen(item); true }
                        }
                    },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun buildItemDescription(item: FileItem): String {
    return if (item.isDir) {
        stringResource(R.string.explorer_a11y_folder, item.name)
    } else {
        val sizeStr = if (item.size >= 0) formatFileSize(item.size) else ""
        if (sizeStr.isNotEmpty()) {
            stringResource(R.string.explorer_a11y_file_size, item.name, sizeStr)
        } else {
            stringResource(R.string.explorer_a11y_file, item.name)
        }
    }
}

@Composable
private fun ItemSupportingText(item: FileItem) {
    val parts = buildList {
        if (!item.isDir && item.size >= 0) add(formatFileSize(item.size))
        item.modTimeEpochMs?.let { ms ->
            if (ms > 0L) add(MOD_DATE_FORMATTER.format(Instant.ofEpochMilli(ms)))
        }
    }
    if (parts.isNotEmpty()) {
        Text(parts.joinToString(" · "))
    }
}

@Composable
private fun EmptyFolder() {
    EmptyState(
        title = stringResource(R.string.explorer_empty_folder),
        body = stringResource(R.string.explorer_empty_folder_body),
        icon = Icons.Filled.FolderOpen,
    )
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    val errDesc = stringResource(R.string.explorer_error_label)
    EmptyState(
        title = message,
        icon = Icons.Filled.Error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive; contentDescription = "$errDesc $message" },
        action = {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.explorer_btn_retry)) }
        },
    )
}
