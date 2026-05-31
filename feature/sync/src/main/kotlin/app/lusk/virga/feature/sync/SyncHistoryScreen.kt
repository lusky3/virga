package app.lusk.virga.feature.sync

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.ui.EmptyState
import java.text.DateFormat
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHistoryScreen(
    onBack: () -> Unit,
    onOpenRun: (Long) -> Unit = {},
    viewModel: SyncHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var overflowExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sync_history_cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sync_history_cd_more))
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sync_history_clear)) },
                            onClick = { overflowExpanded = false; viewModel.clearHistory() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.tasks.isNotEmpty()) {
                TaskAndStatusFilterRow(
                    tasks = state.tasks,
                    selectedTaskId = state.selectedTaskId,
                    statusFilter = state.statusFilter,
                    onSelectTask = viewModel::setFilter,
                    onSelectStatus = viewModel::setStatusFilter,
                )
            }
            if (!state.loading && state.rows.isEmpty()) {
                EmptyState(title = stringResource(R.string.sync_history_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.rows, key = { it.run.id }) { row ->
                        RunCard(
                            row = row,
                            onClick = { onOpenRun(row.run.id) },
                            onRetry = if (SyncHistoryViewModel.isTerminal(row.run.status) &&
                                row.run.status != SyncStatus.SUCCESS
                            ) {
                                { viewModel.retryRun(row.run) }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskAndStatusFilterRow(
    tasks: List<HistoryTaskFilter>,
    selectedTaskId: Long?,
    statusFilter: SyncStatus?,
    onSelectTask: (Long?) -> Unit,
    onSelectStatus: (SyncStatus?) -> Unit,
) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selectedTaskId == null,
                onClick = { onSelectTask(null) },
                label = { Text(stringResource(R.string.sync_history_filter_all)) },
            )
        }
        items(tasks, key = { it.id }) { task ->
            FilterChip(
                selected = selectedTaskId == task.id,
                onClick = { onSelectTask(task.id) },
                label = { Text(task.name, maxLines = 1) },
            )
        }
        item {
            FilterChip(
                selected = statusFilter == SyncStatus.FAILED,
                onClick = {
                    onSelectStatus(if (statusFilter == SyncStatus.FAILED) null else SyncStatus.FAILED)
                },
                label = { Text(stringResource(R.string.sync_history_filter_failed)) },
            )
        }
    }
}

@Composable
internal fun RunCard(
    row: SyncRunRow,
    onClick: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
) {
    val run = row.run
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.taskName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                SyncStatusBadge(run.status)
                if (onRetry != null) {
                    IconButton(onClick = onRetry) {
                        Icon(
                            Icons.Filled.Replay,
                            contentDescription = stringResource(R.string.sync_history_retry),
                        )
                    }
                }
            }
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(run.startedAtEpochMs)),
                style = MaterialTheme.typography.bodySmall,
            )
            val detail = buildString {
                append("${run.filesTransferred} files · ${formatFileSize(run.bytesTransferred)}")
                run.endedAtEpochMs?.let { append(" · ${formatDuration(it - run.startedAtEpochMs)}") }
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            if (run.errorCount > 0 || run.errorMessage != null) {
                Text(
                    run.errorMessage ?: "${run.errorCount} error(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val d = ms.milliseconds
    return when {
        d.inWholeMinutes >= 1 -> "${d.inWholeMinutes}m ${d.inWholeSeconds % 60}s"
        else -> "${d.inWholeSeconds}s"
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "RunCard light", showBackground = true)
@Preview(name = "RunCard dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RunCardPreview() {
    Surface {
        RunCard(
            row = SyncRunRow(
                run = SyncRun(
                    id = 1L, taskId = 1L, status = SyncStatus.SUCCESS,
                    startedAtEpochMs = 0L, endedAtEpochMs = 60_000L,
                    filesTransferred = 42, bytesTransferred = 1_234_567L,
                ),
                taskName = "Photos Backup",
            ),
        )
    }
}

