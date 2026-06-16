package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp

private enum class ImportMode { REPLACE, MERGE }

/**
 * Dialog offering Replace vs Merge import choices.
 *
 * [onConfirmReplace] — replace all existing remotes (existing wipe behaviour).
 * [onConfirmMerge] — merge incoming into existing (overwriteExisting=true for collisions).
 * [onDismiss] — cancel.
 */
@Composable
internal fun ImportModeDialog(
    onConfirmReplace: () -> Unit,
    onConfirmMerge: () -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(ImportMode.REPLACE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remotes_import_mode_title)) },
        text = {
            Column {
                Text(
                    text = if (mode == ImportMode.REPLACE) {
                        stringResource(R.string.remotes_import_mode_replace_warning)
                    } else {
                        stringResource(R.string.remotes_import_mode_merge_info)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                ImportModeSelector(mode = mode, onSelect = { mode = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (mode == ImportMode.REPLACE) onConfirmReplace() else onConfirmMerge() },
            ) { Text(stringResource(R.string.remotes_import_mode_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remotes_delete_cancel))
            }
        },
    )
}

@Composable
private fun ImportModeSelector(mode: ImportMode, onSelect: (ImportMode) -> Unit) {
    Column(Modifier.selectableGroup()) {
        ImportModeRow(
            label = stringResource(R.string.remotes_import_mode_replace_label),
            selected = mode == ImportMode.REPLACE,
            onClick = { onSelect(ImportMode.REPLACE) },
        )
        ImportModeRow(
            label = stringResource(R.string.remotes_import_mode_merge_label),
            selected = mode == ImportMode.MERGE,
            onClick = { onSelect(ImportMode.MERGE) },
        )
    }
}

@Composable
private fun ImportModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
