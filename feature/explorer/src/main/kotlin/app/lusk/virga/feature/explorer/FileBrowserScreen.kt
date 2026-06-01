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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.designsystem.component.EmptyState
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

    if (initialRemote != null) {
        LaunchedEffect(initialRemote) { viewModel.selectRemoteIfUnset(initialRemote) }
    }

    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = state.remoteName != null && !state.atRoot && !state.selectionMode) {
        viewModel.up()
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
                        ExtendedFloatingActionButton(
                            onClick = { viewModel.pickFolder(remote, state.path); onBack() },
                            icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                            text = { Text(pickLabel) },
                        )
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
                    if (state.truncated) {
                        val truncatedDesc = stringResource(R.string.explorer_truncated_notice)
                        Text(
                            truncatedDesc,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
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
    LargeTopAppBar(
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
                        onLongClick = { onLongPress(item.path) },
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val errDesc = stringResource(R.string.explorer_error_label)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive; contentDescription = "$errDesc $message" },
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.explorer_btn_retry)) }
        }
    }
}
