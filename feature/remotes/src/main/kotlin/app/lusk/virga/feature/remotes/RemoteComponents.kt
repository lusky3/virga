package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.lusk.virga.core.designsystem.component.VirgaCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteQuota
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.designsystem.component.RemoteProviderMark
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/** Curated list of common rclone backend types with friendly display names. */
internal val RcloneBackendTypes: List<Pair<String, String>> = listOf(
    "drive"    to "Google Drive",
    "onedrive" to "OneDrive",
    "dropbox"  to "Dropbox",
    "s3"       to "Amazon S3 / compatible",
    "b2"       to "Backblaze B2",
    "sftp"     to "SFTP",
    "ftp"      to "FTP",
    "webdav"   to "WebDAV",
    "mega"     to "MEGA",
    "pcloud"   to "pCloud",
    "box"      to "Box",
    "gphotos"  to "Google Photos",
    "crypt"    to "Encrypted",
    "local"    to "Local disk",
)

@Composable
internal fun RemoteCard(
    remote: Remote,
    onOpenBrowser: () -> Unit,
    onCreateTask: (String) -> Unit,
    onDelete: () -> Unit,
    quota: RemoteQuota? = null,
    quotaLoading: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    VirgaCard(onClick = onOpenBrowser) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val friendlyType = RcloneBackendTypes.firstOrNull { it.first == remote.type }?.second
                ?: remote.type
            RemoteProviderMark(
                type = remote.type,
                contentDescription = friendlyType,
            )
            Column(Modifier.weight(1f).padding(start = VirgaSpacing.sm)) {
                Text(
                    remote.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(friendlyType, style = MaterialTheme.typography.bodyMedium)
                RemoteQuotaRow(quota, quotaLoading)
            }

            // Overflow menu
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.remotes_card_cd_overflow),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remotes_card_menu_browse)) },
                    onClick = { menuExpanded = false; onOpenBrowser() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remotes_card_menu_new_task)) },
                    onClick = { menuExpanded = false; onCreateTask(remote.name) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remotes_card_menu_delete)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                    },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

/**
 * Shows a compact "used of total" line with a progress bar. When the quota is not
 * yet available but a fetch is in flight ([loading]), shows an animated indeterminate
 * "thinking" bar in the same spot so the card doesn't reflow when the real value
 * arrives. Renders nothing when there's no quota and nothing is loading.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RemoteQuotaRow(quota: RemoteQuota?, loading: Boolean) {
    val used = quota?.used
    val total = quota?.total
    val hasQuota = used != null && total != null && total > 0L

    when {
        hasQuota -> {
            val fraction = (used.toFloat() / total).coerceIn(0f, 1f)
            val label = stringResource(
                R.string.remotes_quota_used_of_total,
                formatFileSize(used),
                formatFileSize(total),
            )
            Column(Modifier.fillMaxWidth().padding(top = VirgaSpacing.xs)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearWavyProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = VirgaSpacing.xs),
                )
            }
        }
        loading -> {
            val checkingLabel = stringResource(R.string.remotes_quota_loading)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = VirgaSpacing.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    checkingLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = VirgaSpacing.xs)
                        .semantics { contentDescription = checkingLabel },
                )
            }
        }
    }
}
