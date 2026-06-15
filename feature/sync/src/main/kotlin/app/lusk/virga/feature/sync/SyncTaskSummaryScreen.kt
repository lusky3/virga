package app.lusk.virga.feature.sync

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Verified
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.designsystem.theme.LocalVirgaColors
import app.lusk.virga.core.designsystem.theme.rememberReduceMotion
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
    val dryRun by viewModel.dryRun.collectAsStateWithLifecycle()
    val checkState by viewModel.checkState.collectAsStateWithLifecycle()
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
                liveProgress = state.liveProgress,
                previewAvailable = viewModel.previewAvailable(),
                previewRunning = dryRun.running,
                verify = VerifyActionState(
                    available = viewModel.verifyAvailable(),
                    running = checkState.running,
                    onVerify = viewModel::verifyChanges,
                ),
                modifier = Modifier.padding(padding),
                onSyncNow = viewModel::syncNow,
                onCancelSync = viewModel::cancelSync,
                onToggleEnabled = viewModel::setEnabled,
                onPreview = viewModel::previewChanges,
                onOpenRun = onOpenRun,
                onDelete = { showDelete = true },
            )
        }
    }

    // Dry-run preview result (WS2.3): show the planned change-set with a
    // "Run for real" confirm.
    dryRun.result?.let { result ->
        val err = result.error
        AlertDialog(
            onDismissRequest = viewModel::dismissPreview,
            title = { Text(stringResource(R.string.sync_preview_title)) },
            text = {
                if (err != null) {
                    Text(stringResource(R.string.sync_preview_error, err))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
                        Text(
                            pluralStringResource(
                                R.plurals.sync_preview_body,
                                result.filesToTransfer,
                                result.filesToTransfer,
                                formatFileSize(result.bytesToTransfer),
                            ),
                        )
                        // Name the destructive blast radius before "Run for real" (§13).
                        // rclone skips (and so usually doesn't count) deletes under
                        // --dry-run, so show the precise count when it reports one,
                        // otherwise a qualitative warning for any Mirror task.
                        if (result.filesToDelete > 0) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.sync_preview_deletions,
                                    result.filesToDelete,
                                    result.filesToDelete,
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (result.mirrors) {
                            Text(
                                text = stringResource(R.string.sync_preview_mirror_warning),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (err == null) {
                    TextButton(onClick = { viewModel.dismissPreview(); viewModel.syncNow() }) {
                        Text(stringResource(R.string.sync_preview_run_for_real))
                    }
                } else {
                    TextButton(onClick = viewModel::dismissPreview) {
                        Text(stringResource(R.string.sync_preview_close))
                    }
                }
            },
            dismissButton = {
                if (err == null) {
                    TextButton(onClick = viewModel::dismissPreview) {
                        Text(stringResource(R.string.sync_preview_close))
                    }
                }
            },
        )
    }

    // Verify (check) result dialog: "In sync" or "N files differ".
    checkState.result?.let { result ->
        val err = result.error
        AlertDialog(
            onDismissRequest = viewModel::dismissVerify,
            title = { Text(stringResource(R.string.sync_verify_title)) },
            text = {
                if (err != null) {
                    Text(stringResource(R.string.sync_verify_error, err))
                } else if (result.differences == 0) {
                    Text(stringResource(R.string.sync_verify_in_sync))
                } else {
                    Text(
                        pluralStringResource(
                            R.plurals.sync_verify_differs,
                            result.differences,
                            result.differences,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissVerify) {
                    Text(stringResource(R.string.sync_preview_close))
                }
            },
        )
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

/** Bundles the verify (check) action state to keep [SummaryContent]'s param count bounded. */
private data class VerifyActionState(
    val available: Boolean,
    val running: Boolean,
    val onVerify: () -> Unit,
)

@Composable
private fun SummaryContent(
    task: SyncTask,
    runs: List<SyncRun>,
    liveProgress: SyncProgress?,
    previewAvailable: Boolean,
    previewRunning: Boolean,
    verify: VerifyActionState,
    modifier: Modifier,
    onSyncNow: () -> Unit,
    onCancelSync: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onOpenRun: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    val latest = runs.maxByOrNull { it.startedAtEpochMs }
    val isActive = latest?.status == SyncStatus.RUNNING || latest?.status == SyncStatus.QUEUED

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(VirgaSpacing.md),
        verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
    ) {
        item {
            // Run / Cancel + Enabled toggle.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) {
                    OutlinedButton(onClick = onCancelSync) {
                        Icon(Icons.Filled.Cancel, contentDescription = null)
                        Spacer(Modifier.width(VirgaSpacing.sm))
                        Text(stringResource(R.string.sync_task_cd_cancel))
                    }
                } else {
                    FilledTonalButton(onClick = onSyncNow) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(VirgaSpacing.sm))
                        Text(stringResource(R.string.sync_task_cd_sync_now))
                    }
                    if (previewAvailable) {
                        Spacer(Modifier.width(VirgaSpacing.sm))
                        OutlinedButton(onClick = onPreview, enabled = !previewRunning) {
                            Icon(Icons.Filled.Preview, contentDescription = null)
                            Spacer(Modifier.width(VirgaSpacing.sm))
                            Text(
                                stringResource(
                                    if (previewRunning) R.string.sync_preview_running
                                    else R.string.sync_preview_action,
                                ),
                            )
                        }
                    }
                    if (verify.available) {
                        Spacer(Modifier.width(VirgaSpacing.sm))
                        OutlinedButton(onClick = verify.onVerify, enabled = !verify.running) {
                            Icon(Icons.Filled.Verified, contentDescription = null)
                            Spacer(Modifier.width(VirgaSpacing.sm))
                            Text(
                                stringResource(
                                    if (verify.running) R.string.sync_verify_running
                                    else R.string.sync_verify_action,
                                ),
                            )
                        }
                    }
                }
            }
            if (liveProgress != null) {
                Spacer(Modifier.height(VirgaSpacing.md))
                LiveSyncPanel(progress = liveProgress)
            }
            ToggleRow(
                label = stringResource(R.string.sync_summary_enabled),
                checked = task.enabled,
                onChange = onToggleEnabled,
            )
            HorizontalDivider(Modifier.padding(vertical = VirgaSpacing.sm))
        }

        item {
            SummaryRow(stringResource(R.string.sync_summary_source), task.sourcePath)
            SummaryRow(stringResource(R.string.sync_summary_destination), "${task.remoteName}:${task.remotePath}")
            SummaryRow(stringResource(R.string.sync_summary_direction), stringResource(directionLabelRes(task.direction)))
            if (task.deleteExtraneous) {
                SummaryRow(stringResource(R.string.sync_summary_mirror), stringResource(R.string.sync_summary_mirror_on))
            }
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
            HorizontalDivider(Modifier.padding(vertical = VirgaSpacing.sm))
        }

        item {
            Text(
                stringResource(R.string.sync_summary_recent_runs),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = VirgaSpacing.xs),
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
            HorizontalDivider(Modifier.padding(vertical = VirgaSpacing.sm))
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(VirgaSpacing.sm))
                Text(stringResource(R.string.sync_task_menu_delete))
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = VirgaSpacing.xs)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * Rich live-transfer panel for the summary screen (WS1.1, BRAND §10/§12): a
 * "Backing up…" heading, a `running`-tinted bar (indeterminate while listing),
 * and a metrics line. Determinate fallback for the deferred Expressive wavy bar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LiveSyncPanel(progress: SyncProgress) {
    val running = LocalVirgaColors.current.running
    val reduceMotion = rememberReduceMotion()
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.sync_summary_backing_up),
            style = MaterialTheme.typography.titleMedium,
            color = running,
        )
        Spacer(Modifier.height(VirgaSpacing.sm))
        // Wavy "precipitation" bar (BRAND §12); static determinate under reduce-motion.
        when {
            reduceMotion && progress.totalBytes > 0 ->
                LinearProgressIndicator(progress = { progress.fraction }, color = running, modifier = Modifier.fillMaxWidth())
            reduceMotion ->
                LinearProgressIndicator(color = running, modifier = Modifier.fillMaxWidth())
            progress.totalBytes > 0 ->
                LinearWavyProgressIndicator(progress = { progress.fraction }, color = running, modifier = Modifier.fillMaxWidth())
            else ->
                LinearWavyProgressIndicator(color = running, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(VirgaSpacing.xs))
        val parts = buildList {
            if (progress.totalBytes > 0) add("${(progress.fraction * 100).toInt()}%")
            if (progress.totalFiles > 0) add("${progress.transferredFiles}/${progress.totalFiles} files")
            if (progress.speedBytesPerSec > 0) add("${formatFileSize(progress.speedBytesPerSec.toLong())}/s")
            progress.etaSeconds?.let {
                add("ETA " + if (it >= 60) "${it / 60}m" else "${it}s")
            }
        }
        Text(
            text = if (parts.isEmpty()) stringResource(R.string.sync_summary_starting) else parts.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = VirgaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SyncStatusBadge(status = run.status)
        Spacer(Modifier.width(VirgaSpacing.sm))
        Text(
            "$relTime · ${run.filesTransferred} files$sizeLabel",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
