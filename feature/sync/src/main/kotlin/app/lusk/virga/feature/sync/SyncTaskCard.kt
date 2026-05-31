package app.lusk.virga.feature.sync

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.designsystem.component.VirgaCard
import app.lusk.virga.core.designsystem.component.VirgaCardState
import app.lusk.virga.core.designsystem.theme.LocalVirgaColors

@Composable
internal fun SyncTaskCard(
    task: SyncTask,
    latestRun: SyncRun?,
    selected: Boolean,
    inSelectionMode: Boolean,
    liveProgress: SyncProgress? = null,
    onSyncNow: () -> Unit,
    onCancelSync: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
    onOpenHistory: () -> Unit,
    onDelete: () -> Unit,
) {
    val isActiveRun = latestRun?.status == SyncStatus.RUNNING ||
        latestRun?.status == SyncStatus.QUEUED
    var overflowExpanded by remember { mutableStateOf(false) }
    val cardState = when {
        isActiveRun -> VirgaCardState.Active
        selected -> VirgaCardState.Selected
        else -> VirgaCardState.Default
    }

    VirgaCard(
        state = cardState,
        onClick = onClick,
        onLongClick = onLongClick,
        contentPadding = PaddingValues(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        modifier = Modifier.semantics { onClick(label = "Open task") { false } },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inSelectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (latestRun != null) {
                        Spacer(Modifier.width(6.dp))
                        SyncStatusBadge(status = latestRun.status)
                    }
                }
                Text(
                    text = stringResource(
                        R.string.sync_task_path_summary,
                        task.sourcePath,
                        task.remoteName,
                        task.remotePath,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.sync_task_schedule_summary,
                        task.direction.name.lowercase(),
                        app.lusk.virga.sync.SyncSchedule.describe(
                            task.scheduleDaysMask, task.scheduleHour, task.scheduleMinute,
                        ) ?: task.intervalMinutes?.let { "every ${it}m" } ?: "manual",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (liveProgress != null) {
                    LiveProgressLine(progress = liveProgress)
                } else if (latestRun != null) {
                    LastRunLine(run = latestRun)
                }
            }
            Switch(
                checked = task.enabled,
                onCheckedChange = onToggleEnabled,
            )
            if (isActiveRun) {
                IconButton(onClick = onCancelSync) {
                    Icon(
                        Icons.Filled.Cancel,
                        contentDescription = stringResource(R.string.sync_task_cd_cancel),
                    )
                }
            } else {
                IconButton(onClick = onSyncNow) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.sync_task_cd_sync_now),
                    )
                }
            }
            IconButton(onClick = { overflowExpanded = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.sync_task_cd_more),
                )
            }
            DropdownMenu(
                expanded = overflowExpanded,
                onDismissRequest = { overflowExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_task_menu_run)) },
                    onClick = { onSyncNow(); overflowExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_task_menu_duplicate)) },
                    onClick = { onDuplicate(); overflowExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_task_menu_edit)) },
                    onClick = { onEdit(); overflowExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_task_menu_history)) },
                    onClick = { onOpenHistory(); overflowExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_task_menu_delete)) },
                    onClick = { onDelete(); overflowExpanded = false },
                )
            }
        }
    }
}

/**
 * Live transfer treatment for an active run (BRAND §10/§12): a determinate
 * progress bar tinted `running` (indeterminate while still listing) plus a
 * compact metrics line. The wavy "precipitation" indicator is deferred until
 * the Expressive APIs are public (see Theme.kt); this is the determinate
 * fallback BRAND §12 calls for.
 */
@Composable
private fun LiveProgressLine(progress: SyncProgress) {
    val running = LocalVirgaColors.current.running
    Spacer(Modifier.height(4.dp))
    if (progress.totalBytes > 0) {
        LinearProgressIndicator(
            progress = { progress.fraction },
            color = running,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(
            color = running,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(2.dp))
    val parts = buildList {
        if (progress.totalBytes > 0) add("${(progress.fraction * 100).toInt()}%")
        if (progress.totalFiles > 0) add("${progress.transferredFiles}/${progress.totalFiles} files")
        if (progress.speedBytesPerSec > 0) add("${formatFileSize(progress.speedBytesPerSec.toLong())}/s")
        progress.etaSeconds?.let { add("ETA ${formatEta(it)}") }
    }
    Text(
        text = if (parts.isEmpty()) "Starting…" else parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = running,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun formatEta(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60}m"
    else -> "${seconds}s"
}

@Composable
private fun LastRunLine(run: SyncRun) {
    val relTime = remember(run.startedAtEpochMs) {
        DateUtils.getRelativeTimeSpanString(
            run.startedAtEpochMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }
    val statusLabel = run.status.name.lowercase()
    val sizeLabel = if (run.bytesTransferred > 0) " · ${formatFileSize(run.bytesTransferred)}" else ""
    val line = "Last: $relTime · $statusLabel · ${run.filesTransferred} files$sizeLabel"
    val color = if (run.status == SyncStatus.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = line,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
