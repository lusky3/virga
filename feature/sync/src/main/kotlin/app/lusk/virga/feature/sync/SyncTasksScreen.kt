package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.ui.EmptyState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncTasksScreen(
    onAddTask: () -> Unit,
    onOpenTask: (Long) -> Unit,
    onEditTask: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenConflicts: () -> Unit,
    viewModel: SyncTasksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Collapse FAB once the user scrolls down.
    val fabExpanded by remember { derivedStateOf { !listState.canScrollBackward } }

    // One-shot VM messages (e.g. "Sync started", "Task duplicated").
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // Dialog state for single-task delete confirmation.
    var taskToDelete by remember { mutableStateOf<SyncTask?>(null) }
    // Dialog state for bulk-delete confirmation.
    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    val inSelectionMode = state.selectedIds.isNotEmpty()

    Scaffold(
        topBar = {
            if (inSelectionMode) {
                SelectionTopBar(
                    count = state.selectedIds.size,
                    onClear = viewModel::clearSelection,
                    onBulkRun = viewModel::bulkRun,
                    onBulkEnable = { viewModel.bulkSetEnabled(true) },
                    onBulkDisable = { viewModel.bulkSetEnabled(false) },
                    onBulkDelete = { showBulkDeleteDialog = true },
                )
            } else {
                MainTopBar(
                    conflictCount = state.unresolvedConflictCount,
                    anyRunActive = state.anyRunActive,
                    onConflicts = onOpenConflicts,
                    onHistory = onOpenHistory,
                    onSyncAll = viewModel::syncAllEnabled,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!inSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onAddTask,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.sync_task_fab_new)) },
                    expanded = fabExpanded,
                )
            }
        },
    ) { padding ->
        val isFiltered = state.searchQuery.isNotBlank() || state.activeFilter != TaskFilter.ALL

        when {
            !state.loading && state.tasks.isEmpty() && !isFiltered -> {
                EmptyState(
                    title = stringResource(R.string.sync_tasks_empty_title),
                    body = stringResource(R.string.sync_tasks_empty_body),
                    icon = Icons.Filled.SyncProblem,
                    action = {
                        Button(onClick = onAddTask) {
                            Text(stringResource(R.string.sync_tasks_empty_action))
                        }
                    },
                    modifier = Modifier.padding(padding),
                )
            }

            !state.loading && state.tasks.isEmpty() && isFiltered -> {
                EmptyState(
                    title = stringResource(R.string.sync_tasks_no_match_title),
                    modifier = Modifier.padding(padding),
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!inSelectionMode) {
                        item(key = "search_bar") {
                            SyncTaskSearchBar(
                                query = state.searchQuery,
                                activeFilter = state.activeFilter,
                                sortOrder = state.sortOrder,
                                onQueryChange = viewModel::setSearch,
                                onFilterChange = viewModel::setFilter,
                                onSortChange = viewModel::setSort,
                            )
                        }
                    }
                    items(state.tasks, key = { it.id }) { task ->
                        val latestRun = state.latestRuns[task.id]
                        val deletedLabel = stringResource(R.string.sync_task_deleted_snackbar, task.name)
                        val undoLabel = stringResource(R.string.sync_task_deleted_snackbar_undo)
                        SwipeToDeleteCard(
                            onDelete = {
                                viewModel.markPendingSwipeDelete(task)
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        message = deletedLabel,
                                        actionLabel = undoLabel,
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.undoSwipeDelete(task)
                                    } else {
                                        viewModel.commitSwipeDelete(task)
                                    }
                                }
                            },
                        ) {
                            SyncTaskCard(
                                task = task,
                                latestRun = latestRun,
                                selected = task.id in state.selectedIds,
                                inSelectionMode = inSelectionMode,
                                onSyncNow = { viewModel.syncNow(task.id) },
                                onCancelSync = { viewModel.cancelSync(task.id) },
                                onToggleEnabled = { viewModel.setEnabled(task, it) },
                                onClick = {
                                    if (inSelectionMode) viewModel.toggleSelection(task.id)
                                    else onOpenTask(task.id)
                                },
                                onLongClick = { viewModel.toggleSelection(task.id) },
                                onDuplicate = { viewModel.duplicate(task) },
                                onEdit = { onEditTask(task.id) },
                                onOpenHistory = onOpenHistory,
                                onDelete = { taskToDelete = task },
                            )
                        }
                    }
                }
            }
        }
    }

    // Single-task delete confirmation dialog.
    taskToDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToDelete = null },
            title = { Text(stringResource(R.string.sync_task_delete_dialog_title)) },
            text = { Text(stringResource(R.string.sync_task_delete_dialog_body, task.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(task)
                    taskToDelete = null
                }) { Text(stringResource(R.string.sync_task_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { taskToDelete = null }) {
                    Text(stringResource(R.string.sync_task_delete_cancel))
                }
            },
        )
    }

    // Bulk-delete confirmation dialog.
    if (showBulkDeleteDialog) {
        val count = state.selectedIds.size
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = { Text(stringResource(R.string.sync_task_bulk_delete_title, count)) },
            text = { Text(stringResource(R.string.sync_task_bulk_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmBulkDelete()
                    showBulkDeleteDialog = false
                }) { Text(stringResource(R.string.sync_task_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) {
                    Text(stringResource(R.string.sync_task_delete_cancel))
                }
            },
        )
    }
}
