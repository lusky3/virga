package app.lusk.virga.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.common.model.SyncStatus

/**
 * Non-interactive Material 3 status pill showing a [SyncStatus] with
 * container/on-container color pairs. Replaces both the plain-text
 * RunStatusBadge in SyncTasksScreen and the AssistChip StatusChip in
 * SyncHistoryScreen so all status surfaces are consistent.
 */
@Composable
fun SyncStatusBadge(status: SyncStatus, modifier: Modifier = Modifier) {
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
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        modifier = modifier
            .background(containerColor, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = "Status: $label" },
    )
}
