package app.lusk.virga.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.designsystem.theme.LocalVirgaColors

/**
 * The single canonical status renderer for the whole app (BRAND §10): every
 * task/run state has exactly one representation = container color + glyph +
 * label text — never color alone. Semantic colors come from
 * [LocalVirgaColors] (success/running/info), with M3 error roles for failures
 * and surfaceVariant for idle/cancelled. This replaces the previous
 * tertiaryContainer-shoehorned success mapping.
 */
@Composable
fun SyncStatusBadge(status: SyncStatus, modifier: Modifier = Modifier) {
    val virga = LocalVirgaColors.current
    val scheme = MaterialTheme.colorScheme

    data class BadgeStyle(
        val labelRes: Int,
        val container: Color,
        val content: Color,
        val glyph: ImageVector?,
    )

    val style = when (status) {
        SyncStatus.SUCCESS -> BadgeStyle(
            R.string.sync_history_status_success,
            virga.successContainer, virga.onSuccessContainer, Icons.Filled.CheckCircle,
        )
        SyncStatus.FAILED -> BadgeStyle(
            R.string.sync_history_status_failed,
            scheme.errorContainer, scheme.onErrorContainer, Icons.Filled.Error,
        )
        SyncStatus.RUNNING -> BadgeStyle(
            R.string.sync_history_status_running,
            virga.runningContainer, virga.onRunningContainer, Icons.Filled.Sync,
        )
        SyncStatus.QUEUED -> BadgeStyle(
            R.string.sync_history_status_queued,
            virga.infoContainer, virga.onInfoContainer, Icons.Filled.Schedule,
        )
        SyncStatus.CANCELLED -> BadgeStyle(
            R.string.sync_history_status_cancelled,
            scheme.surfaceVariant, scheme.onSurfaceVariant, Icons.Filled.PauseCircle,
        )
        SyncStatus.IDLE -> BadgeStyle(
            R.string.sync_history_status_idle,
            scheme.surfaceVariant, scheme.onSurfaceVariant, null,
        )
    }
    val label = stringResource(style.labelRes)
    Row(
        modifier = modifier
            .background(style.container, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = "Status: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (style.glyph != null) {
            Icon(
                style.glyph,
                contentDescription = null,
                tint = style.content,
                modifier = Modifier.size(14.dp).padding(end = 2.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = style.content,
        )
    }
}
