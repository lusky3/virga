package app.lusk.virga.feature.sync

import android.content.res.Configuration
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.database.entity.SyncRunEntity
import java.text.DateFormat
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHistoryScreen(
    onBack: () -> Unit,
    viewModel: SyncHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
            )
        },
    ) { padding ->
        if (!state.loading && state.rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.sync_history_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.rows, key = { it.run.id }) { row ->
                    RunCard(row)
                }
            }
        }
    }
}

@Composable
internal fun RunCard(row: SyncRunRow) {
    val run = row.run
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.taskName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(run.status)
            }
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(run.startedAtEpochMs)),
                style = MaterialTheme.typography.bodySmall,
            )
            val detail = buildString {
                append("${run.filesTransferred} files • ${formatFileSize(run.bytesTransferred)}")
                run.endedAtEpochMs?.let { append(" • ${formatDuration(it - run.startedAtEpochMs)}") }
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

@Composable
private fun StatusChip(status: SyncStatus) {
    val (labelRes, containerColor, contentColor) = when (status) {
        SyncStatus.SUCCESS -> Triple(
            R.string.sync_history_status_success,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        SyncStatus.FAILED -> Triple(
            R.string.sync_history_status_failed,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        SyncStatus.RUNNING -> Triple(
            R.string.sync_history_status_running,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        SyncStatus.QUEUED -> Triple(
            R.string.sync_history_status_queued,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        SyncStatus.CANCELLED -> Triple(
            R.string.sync_history_status_cancelled,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SyncStatus.IDLE -> Triple(
            R.string.sync_history_status_idle,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val label = stringResource(labelRes)
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = contentColor,
        ),
        modifier = Modifier.semantics { contentDescription = label },
    )
}

private fun formatDuration(ms: Long): String {
    val d = ms.milliseconds
    return when {
        d.inWholeMinutes >= 1 -> "${d.inWholeMinutes}m ${d.inWholeSeconds % 60}s"
        else -> "${d.inWholeSeconds}s"
    }
}

// ---------------------------------------------------------------------------
// Previews (Task #26)
// ---------------------------------------------------------------------------

@Preview(name = "RunCard light", showBackground = true)
@Preview(name = "RunCard dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "RunCard fontScale=2", showBackground = true, fontScale = 2f)
@Composable
private fun RunCardPreview() {
    Surface {
        RunCard(
            row = SyncRunRow(
                run = SyncRunEntity(
                    id = 1L, taskId = 1L, status = SyncStatus.SUCCESS,
                    startedAtEpochMs = 0L, endedAtEpochMs = 60_000L,
                    filesTransferred = 42, bytesTransferred = 1_234_567L,
                ),
                taskName = "Photos Backup",
            ),
        )
    }
}
