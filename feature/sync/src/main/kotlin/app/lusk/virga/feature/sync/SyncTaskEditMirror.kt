package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * Tier-1 Mirror toggle (WS2.2, BRAND §13). Off by default. Turning it ON is
 * destructive (delete-extraneous), so it routes through an error-tinted
 * acknowledgement that names the consequence before the flag is set; turning it
 * OFF is immediate. When [inert] (e.g. a DOWNLOAD into a SAF folder, where the
 * write-back never deletes), the toggle is disabled and shows an explanatory note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MirrorToggleRow(enabled: Boolean, onChange: (Boolean) -> Unit, inert: Boolean = false) {
    var showConfirm by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = enabled,
                enabled = !inert,
                role = Role.Switch,
                onValueChange = { v -> if (v) showConfirm = true else onChange(false) },
            )
            .padding(vertical = VirgaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sync_edit_mirror_label), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    if (inert) R.string.sync_edit_mirror_inert_download else R.string.sync_edit_mirror_sub,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, enabled = !inert, onCheckedChange = null)
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.sync_edit_mirror_confirm_title)) },
            text = { Text(stringResource(R.string.sync_edit_mirror_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = { showConfirm = false; onChange(true) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.sync_edit_mirror_confirm_enable)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.sync_edit_mirror_confirm_cancel))
                }
            },
        )
    }
}
