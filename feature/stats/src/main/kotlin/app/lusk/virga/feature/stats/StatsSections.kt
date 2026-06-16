package app.lusk.virga.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.lusk.virga.core.common.model.RemoteQuotaState
import app.lusk.virga.core.common.model.RemoteStat
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.designsystem.component.VirgaCard
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

@Composable
internal fun RemoteStatsSection(
    remoteStats: List<RemoteStat>,
    quotas: List<RemoteQuotaState>,
    onResetRemote: (String) -> Unit,
) {
    if (remoteStats.isEmpty()) return
    val quotaByName = quotas.associateBy { it.remoteName }
    SectionLabel(stringResource(R.string.stats_section_by_remote))
    Column(modifier = Modifier.testTag("stats_remote_section")) {
        remoteStats.filter { it.remoteName.isNotBlank() }.forEach { stat ->
            val quota = quotaByName[stat.remoteName]
            RemoteStatCard(stat = stat, quota = quota, onReset = { onResetRemote(stat.remoteName) })
        }
    }
}

@Composable
private fun RemoteStatCard(
    stat: RemoteStat,
    quota: RemoteQuotaState?,
    onReset: () -> Unit,
) {
    VirgaCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(stat.remoteName, style = MaterialTheme.typography.titleSmall)
                Text(
                    formatFileSize(stat.bytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.stats_remote_detail, stat.totalRuns, stat.successRuns),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val quotaTotal = quota?.total
                if (quotaTotal != null) {
                    Text(
                        stringResource(
                            R.string.stats_remote_quota,
                            formatFileSize(quota.free ?: 0L),
                            formatFileSize(quotaTotal),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onReset) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.stats_reset_remote_cd, stat.remoteName),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun TaskStatsSection(taskStats: List<TaskStatUi>) {
    if (taskStats.isEmpty()) return
    SectionLabel(stringResource(R.string.stats_section_by_task))
    Column(modifier = Modifier.testTag("stats_task_section")) {
        taskStats.forEach { stat ->
            VirgaCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stat.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        formatFileSize(stat.bytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.stats_task_detail, stat.totalRuns, stat.successRuns),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun RemoteResetConfirmDialog(
    remoteName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_reset_remote_title)) },
        text = { Text(stringResource(R.string.stats_reset_remote_body, remoteName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.stats_reset_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.stats_reset_cancel))
            }
        },
    )
}
