package app.lusk.virga.feature.sync

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.designsystem.component.EmptyState
import app.lusk.virga.core.designsystem.component.ToggleRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncTaskSummaryScreen(
    taskId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenRun: (Long) -> Unit,
    viewModel: SyncTaskSummaryViewModel = hiltViewModel(),
) {
    LaunchedEffect(taskId) { viewModel.load(taskId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDelete by remember { mutableStateOf(false) }

    val task = state.task
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task?.name ?: stringResource(R.string.sync_summary_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.sync_summary_cd_back))
                    }
                },
                actions = {
                    if (task != null) {
                        IconButton(onClick = { onEdit(task.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.sync_task_menu_edit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> CircularProgressIndicator(Modifier.padding(padding))
            task == null -> EmptyState(
                title = stringResource(R.string.sync_summary_missing),
                modifier = Modifier.padding(padding),
            )
            else -> SummaryContent(
                task = task,
                runs = state.runs,
                modifier = Modifier.padding(padding),
                onSyncNow = viewModel::syncNow,
                onCancelSync = viewModel::cancelSync,
                onToggleEnabled = viewModel::setEnabled,
                onOpenRun = onOpenRun,
                onDelete = { showDelete = true },
            )
        }
    }

    if (showDelete && task != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.sync_task_delete_dialog_title)) },
            text = { Text(stringResource(R.string.sync_task_delete_dialog_body, task.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text(stringResource(R.string.sync_task_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.sync_task_delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun SummaryContent(
    task: SyncTask,
    runs: List<SyncRun>,
    modifier: Modifier,
    onSyncNow: () -> Unit,
    onCancelSync: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenRun: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    val latest = runs.maxByOrNull { it.startedAtEpochMs }
    val isActive = latest?.status == SyncStatus.RUNNING || latest?.status == SyncStatus.QUEUED

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            // Run / Cancel + Enabled toggle.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    OutlinedButton(onClick = onCancelSync) {
                        Icon(Icons.Filled.Cancel, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sync_task_cd_cancel))
                    }
                } else {
                    FilledTonalButton(onClick = onSyncNow) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sync_task_cd_sync_now))
                    }
                }
            }
            ToggleRow(
                label = stringResource(R.string.sync_summary_enabled),
                checked = task.enabled,
                onChange = onToggleEnabled,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        item {
            SummaryRow(stringResource(R.string.sync_summary_source), task.sourcePath)
            SummaryRow(stringResource(R.string.sync_summary_destination), "${task.remoteName}:${task.remotePath}")
            SummaryRow(stringResource(R.string.sync_summary_direction), task.direction.name.lowercase())
            SummaryRow(
                stringResource(R.string.sync_summary_schedule),
                app.lusk.virga.sync.SyncSchedule.describe(task.scheduleDaysMask, task.scheduleHour, task.scheduleMinute)
                    ?: task.intervalMinutes?.let { stringResource(R.string.sync_summary_every_minutes, it) }
                    ?: stringResource(R.string.sync_summary_manual),
            )
            if (task.filters.isNotBlank()) {
                SummaryRow(stringResource(R.string.sync_summary_filters), task.filters.lines().filter { it.isNotBlank() }.joinToString(", "))
            }
            val bw = listOfNotNull(
                task.bwLimitWifi?.takeIf { it.isNotBlank() }?.let { "Wi-Fi $it" },
                task.bwLimitMetered?.takeIf { it.isNotBlank() }?.let { "metered $it" },
            ).joinToString(", ")
            if (bw.isNotEmpty()) SummaryRow(stringResource(R.string.sync_summary_bandwidth), bw)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }

        item {
            Text(
                stringResource(R.string.sync_summary_recent_runs),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (runs.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.sync_summary_no_runs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(runs.take(20), key = { it.id }) { run ->
                RunRow(run = run, onClick = { onOpenRun(run.id) })
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sync_task_menu_delete))
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RunRow(run: SyncRun, onClick: () -> Unit) {
    val relTime = remember(run.startedAtEpochMs) {
        DateUtils.getRelativeTimeSpanString(
            run.startedAtEpochMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }
    val sizeLabel = if (run.bytesTransferred > 0) " · ${formatFileSize(run.bytesTransferred)}" else ""
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SyncStatusBadge(status = run.status)
        Spacer(Modifier.width(8.dp))
        Text(
            "$relTime · ${run.filesTransferred} files$sizeLabel",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
