package app.lusk.virga.feature.sync

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.designsystem.component.EmptyState
import app.lusk.virga.core.designsystem.component.VirgaCard
import app.lusk.virga.core.designsystem.theme.LocalSharedTransitionScope
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import app.lusk.virga.core.designsystem.theme.rememberReduceMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    val pagedItems = viewModel.pagedRuns.collectAsLazyPagingItems()
    var overflowExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
                            text = { Text(stringResource(R.string.sync_history_export_csv)) },
                            onClick = {
                                overflowExpanded = false
                                scope.launch { shareExport(context, viewModel, csv = true) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sync_history_export_json)) },
                            onClick = {
                                overflowExpanded = false
                                scope.launch { shareExport(context, viewModel, csv = false) }
                            },
                        )
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
            HistorySearchField(
                query = state.searchQuery,
                onQueryChange = viewModel::setSearchQuery,
            )
            StatusFilterRow(
                statusFilter = state.statusFilter,
                onSelectStatus = viewModel::setStatusFilter,
            )
            if (state.tasks.isNotEmpty()) {
                TaskFilterChips(
                    tasks = state.tasks,
                    selectedTaskId = state.selectedTaskId,
                    onSelectTask = viewModel::setFilter,
                )
            }
            if (pagedItems.loadState.refresh is LoadState.Loading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(VirgaSpacing.md),
                )
            } else if (pagedItems.loadState.refresh is LoadState.NotLoading && pagedItems.itemCount == 0) {
                EmptyState(title = stringResource(R.string.sync_history_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(VirgaSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md),
                ) {
                    items(pagedItems.itemCount) { index ->
                        val row = pagedItems[index]
                        if (row != null) {
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
}

private suspend fun shareExport(context: Context, viewModel: SyncHistoryViewModel, csv: Boolean) {
    val outcome = runCatching {
        val ext = if (csv) "csv" else "json"
        val mimeType = if (csv) "text/csv" else "application/json"
        val uri = withContext(Dispatchers.IO) {
            val rows = viewModel.exportRows()
            val content = if (csv) SyncHistoryExporter.toCsv(rows) else SyncHistoryExporter.toJson(rows)
            val dir = File(context.cacheDir, "shared").also { it.mkdirs() }
            val file = File(dir, "history_export.$ext")
            file.writeText(content)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
    // Surface failures instead of crashing the launched coroutine; never swallow cancellation.
    outcome.exceptionOrNull()?.let { e ->
        if (e is CancellationException) throw e
        Toast.makeText(context, context.getString(R.string.sync_history_export_failed), Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun RunCard(
    row: SyncRunRow,
    onClick: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
) {
    val run = row.run
    val sharedScope = LocalSharedTransitionScope.current
    val reduceMotion = rememberReduceMotion()
    val sharedBoundsModifier = if (sharedScope != null && !reduceMotion) {
        val animScope = LocalNavAnimatedContentScope.current
        with(sharedScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "run-card-${run.id}"),
                animatedVisibilityScope = animScope,
            )
        }
    } else {
        Modifier
    }
    VirgaCard(onClick = onClick, modifier = sharedBoundsModifier) {
        Column(Modifier.fillMaxWidth()) {
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
