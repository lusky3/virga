package app.lusk.virga.feature.sync

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.designsystem.component.EmptyState
import app.lusk.virga.core.designsystem.theme.LocalSharedTransitionScope
import app.lusk.virga.core.designsystem.theme.rememberReduceMotion
import java.text.DateFormat
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun RunDetailScreen(
    runId: Long,
    onBack: () -> Unit,
    onViewLog: (String) -> Unit = {},
    viewModel: RunDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(runId) { viewModel.load(runId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // LocalSharedTransitionScope is nullable (null outside the NavDisplay wrapper,
    // e.g. @Preview); LocalNavAnimatedContentScope is NOT — it throws if read
    // outside a NavEntry — so only read it once we know we're in the provider.
    val sharedScope = LocalSharedTransitionScope.current
    val reduceMotion = rememberReduceMotion()
    val sharedBoundsModifier = if (sharedScope != null && !reduceMotion) {
        val animScope = LocalNavAnimatedContentScope.current
        with(sharedScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "run-card-$runId"),
                animatedVisibilityScope = animScope,
            )
        }
    } else {
        Modifier
    }

    Box(modifier = sharedBoundsModifier.fillMaxSize()) {
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
                onViewLog = onViewLog,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(VirgaSpacing.md),
            )
        }
    }
    } // Box (sharedBounds container)
}

@Composable
private fun RunDetailContent(
    run: SyncRun,
    taskName: String,
    onViewLog: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md)) {
        run.logPath?.let { path ->
            FilledTonalButton(onClick = { onViewLog(path) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null)
                Spacer(Modifier.width(VirgaSpacing.sm))
                Text(stringResource(R.string.run_detail_view_log))
            }
        }
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
