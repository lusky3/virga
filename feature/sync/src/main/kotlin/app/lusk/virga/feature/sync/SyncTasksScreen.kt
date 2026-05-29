package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.database.entity.SyncRunEntity
import android.content.res.Configuration
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.database.entity.SyncTaskEntity
import androidx.compose.material3.Surface
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncTasksScreen(
    onAddTask: () -> Unit,
    onEditTask: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenConflicts: () -> Unit,
    viewModel: SyncTasksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var taskToDelete by remember { mutableStateOf<SyncTaskEntity?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_tasks_title)) },
                actions = {
                    IconButton(onClick = onOpenConflicts) {
                        Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.sync_task_cd_conflicts))
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = stringResource(R.string.sync_task_cd_history))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTask) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sync_task_cd_add))
            }
        },
    ) { padding ->
        if (!state.loading && state.tasks.isEmpty()) {
            EmptyTasks(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.tasks, key = { it.id }) { task ->
                    val latestRun = state.latestRuns[task.id]
                    val isRunning = latestRun?.status == SyncStatus.RUNNING
                    val isQueued = latestRun?.status == SyncStatus.QUEUED
                    SyncTaskCard(
                        task = task,
                        latestRun = latestRun,
                        onRun = { viewModel.syncNow(task.id) },
                        onCancel = { viewModel.cancelSync(task.id) },
                        onToggle = { viewModel.setEnabled(task, it) },
                        onClick = { onEditTask(task.id) },
                        onDelete = { taskToDelete = task },
                        isRunningOrQueued = isRunning || isQueued,
                    )
                }
            }
        }
    }

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
}

@Composable
private fun SyncTaskCard(
    task: SyncTaskEntity,
    latestRun: SyncRunEntity?,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    isRunningOrQueued: Boolean,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    latestRun?.let { RunStatusBadge(it.status, Modifier.padding(start = 6.dp)) }
                }
                Text(
                    "${task.sourcePath} → ${task.remoteName}:${task.remotePath}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${task.direction.name.lowercase()} • " +
                        (task.intervalMinutes?.let { "every ${it}m" } ?: "manual"),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Switch(checked = task.enabled, onCheckedChange = onToggle)
            if (isRunningOrQueued) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Cancel, contentDescription = stringResource(R.string.sync_task_cd_cancel))
                }
            } else {
                IconButton(onClick = onRun) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.sync_task_cd_sync_now))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.sync_task_cd_delete))
            }
        }
    }
}

@Composable
private fun RunStatusBadge(status: SyncStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        SyncStatus.RUNNING -> stringResource(R.string.sync_history_status_running) to MaterialTheme.colorScheme.primary
        SyncStatus.QUEUED -> stringResource(R.string.sync_history_status_queued) to MaterialTheme.colorScheme.tertiary
        SyncStatus.FAILED -> stringResource(R.string.sync_history_status_failed) to MaterialTheme.colorScheme.error
        else -> return
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

@Composable
private fun EmptyTasks(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.sync_tasks_empty),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews (Task #26)
// ---------------------------------------------------------------------------

@Preview(name = "SyncTaskCard light", showBackground = true)
@Preview(name = "SyncTaskCard dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "SyncTaskCard fontScale=2", showBackground = true, fontScale = 2f)
@Composable
private fun SyncTaskCardPreview() {
    Surface {
        SyncTaskCard(
            task = SyncTaskEntity(
                id = 1L, name = "Photos Backup",
                sourcePath = "/storage/emulated/0/DCIM",
                remoteName = "gdrive", remotePath = "/Backup",
                direction = SyncDirection.UPLOAD, intervalMinutes = 60,
            ),
            latestRun = null,
            onRun = {}, onCancel = {}, onToggle = {}, onClick = {}, onDelete = {},
            isRunningOrQueued = false,
        )
    }
}
