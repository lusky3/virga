package app.lusk.virga.feature.sync

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.ui.EmptyState
import java.text.DateFormat
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(
    runId: Long,
    onBack: () -> Unit,
    viewModel: RunDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(runId) { viewModel.load(runId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.run_detail_title)) },
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
        val run = state.run
        if (!state.loading && run == null) {
            EmptyState(
                title = stringResource(R.string.run_detail_not_found),
                modifier = Modifier.padding(padding),
            )
        } else if (run != null) {
            RunDetailContent(
                run = run,
                taskName = state.taskName,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun RunDetailContent(
    run: SyncRunEntity,
    taskName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (taskName.isNotBlank()) {
            Text(taskName, style = MaterialTheme.typography.titleLarge)
        }
        SyncStatusBadge(run.status)
        HorizontalDivider()
        DetailRow(
            label = stringResource(R.string.run_detail_started),
            value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(run.startedAtEpochMs)),
        )
        run.endedAtEpochMs?.let { endMs ->
            DetailRow(
                label = stringResource(R.string.run_detail_ended),
                value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(endMs)),
            )
            DetailRow(
                label = stringResource(R.string.run_detail_duration),
                value = formatDurationMs(endMs - run.startedAtEpochMs),
            )
        }
        HorizontalDivider()
        DetailRow(
            label = stringResource(R.string.run_detail_files),
            value = run.filesTransferred.toString(),
        )
        DetailRow(
            label = stringResource(R.string.run_detail_bytes),
            value = formatFileSize(run.bytesTransferred),
        )
        if (run.errorCount > 0) {
            DetailRow(
                label = stringResource(R.string.run_detail_errors),
                value = run.errorCount.toString(),
                valueColor = MaterialTheme.colorScheme.error,
            )
        }
        val logText = run.errorMessage
        if (!logText.isNullOrBlank()) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.run_detail_log),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { copyToClipboard(context, logText) }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.run_detail_copy_log),
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = logText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

private fun formatDurationMs(ms: Long): String {
    val d = ms.milliseconds
    return when {
        d.inWholeMinutes >= 1 -> "${d.inWholeMinutes}m ${d.inWholeSeconds % 60}s"
        else -> "${d.inWholeSeconds}s"
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("sync_log", text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    clipboard.setPrimaryClip(clip)
}
