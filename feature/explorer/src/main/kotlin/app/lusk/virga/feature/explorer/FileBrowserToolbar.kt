package app.lusk.virga.feature.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.lusk.virga.core.designsystem.component.EmptyState

/** Title block for the LargeTopAppBar — shows remote/breadcrumb or an inline search field. */
@Composable
internal fun FileBrowserTitle(
    state: FileBrowserUiState,
    onSearchQuery: (String) -> Unit,
) {
    if (state.remoteName != null && state.searchActive) {
        TextField(
            value = state.searchQuery,
            onValueChange = onSearchQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.explorer_search_hint)) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    } else {
        Column {
            Text(
                state.remoteName ?: stringResource(R.string.explorer_title_browse),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.remoteName != null) {
                Text(
                    "/" + state.breadcrumb.joinToString("/"),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Action icons for the LargeTopAppBar: search toggle, sort dropdown. */
@Composable
internal fun FileBrowserActions(
    state: FileBrowserUiState,
    onToggleSearch: () -> Unit,
    onSort: (SortConfig) -> Unit,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    if (state.remoteName != null) {
        IconButton(onClick = onToggleSearch) {
            Icon(
                if (state.searchActive) Icons.Filled.Close else Icons.Filled.Search,
                contentDescription = stringResource(
                    if (state.searchActive) R.string.explorer_cd_search_close
                    else R.string.explorer_cd_search_open,
                ),
            )
        }
        IconButton(onClick = { sortMenuExpanded = true }) {
            Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.explorer_cd_sort))
        }
        SortDropdownMenu(
            expanded = sortMenuExpanded,
            current = state.sortConfig,
            onDismiss = { sortMenuExpanded = false },
            onSelect = { config -> sortMenuExpanded = false; onSort(config) },
        )
    }
}

@Composable
private fun SortDropdownMenu(
    expanded: Boolean,
    current: SortConfig,
    onDismiss: () -> Unit,
    onSelect: (SortConfig) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortOption(
            label = stringResource(R.string.explorer_sort_name_asc),
            selected = current == SortConfig(SortField.NAME, SortOrder.ASC),
            onClick = { onSelect(SortConfig(SortField.NAME, SortOrder.ASC)) },
        )
        SortOption(
            label = stringResource(R.string.explorer_sort_name_desc),
            selected = current == SortConfig(SortField.NAME, SortOrder.DESC),
            onClick = { onSelect(SortConfig(SortField.NAME, SortOrder.DESC)) },
        )
        SortOption(
            label = stringResource(R.string.explorer_sort_size_asc),
            selected = current == SortConfig(SortField.SIZE, SortOrder.ASC),
            onClick = { onSelect(SortConfig(SortField.SIZE, SortOrder.ASC)) },
        )
        SortOption(
            label = stringResource(R.string.explorer_sort_size_desc),
            selected = current == SortConfig(SortField.SIZE, SortOrder.DESC),
            onClick = { onSelect(SortConfig(SortField.SIZE, SortOrder.DESC)) },
        )
        SortOption(
            label = stringResource(R.string.explorer_sort_modified_asc),
            selected = current == SortConfig(SortField.MODIFIED, SortOrder.ASC),
            onClick = { onSelect(SortConfig(SortField.MODIFIED, SortOrder.ASC)) },
        )
        SortOption(
            label = stringResource(R.string.explorer_sort_modified_desc),
            selected = current == SortConfig(SortField.MODIFIED, SortOrder.DESC),
            onClick = { onSelect(SortConfig(SortField.MODIFIED, SortOrder.DESC)) },
        )
    }
}

@Composable
private fun SortOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        },
        onClick = onClick,
    )
}

/**
 * Action bar shown in selection mode. Delete is always enabled; Rename is only
 * enabled when exactly one item is selected.
 *
 * Callbacks are grouped into [SelectionActions] to stay within the 6-parameter limit.
 */
data class SelectionActions(
    val onDelete: () -> Unit,
    val onRename: () -> Unit,
    val onMove: () -> Unit,
    val onCopy: () -> Unit,
)

@Composable
internal fun SelectionActionBar(
    selectionCount: Int,
    actions: SelectionActions,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.explorer_selection_count, selectionCount, selectionCount),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = actions.onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.explorer_action_delete))
            }
            IconButton(onClick = actions.onRename, enabled = selectionCount == 1) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.explorer_action_rename))
            }
            IconButton(onClick = actions.onMove) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.explorer_action_move))
            }
            IconButton(onClick = actions.onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.explorer_action_copy))
            }
        }
    }
}

/** Remote picker list shown at the top level when no remote is selected. */
@Composable
internal fun RemotePicker(
    remotes: List<String>,
    onSelect: (String) -> Unit,
    onNavigateToRemotes: () -> Unit,
) {
    if (remotes.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.explorer_no_remotes),
            action = {
                TextButton(onClick = onNavigateToRemotes) {
                    Text(stringResource(R.string.explorer_add_remote))
                }
            },
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(remotes, key = { it }) { name ->
            ListItem(
                headlineContent = {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                modifier = Modifier.clickable(role = Role.Button) { onSelect(name) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
internal fun EmptyFolder() {
    EmptyState(
        title = stringResource(R.string.explorer_empty_folder),
        body = stringResource(R.string.explorer_empty_folder_body),
        icon = Icons.Filled.FolderOpen,
    )
}

@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit) {
    val errDesc = stringResource(R.string.explorer_error_label)
    EmptyState(
        title = message,
        icon = Icons.Filled.Error,
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Assertive
            contentDescription = "$errDesc $message"
        },
        action = {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.explorer_btn_retry)) }
        },
    )
}
