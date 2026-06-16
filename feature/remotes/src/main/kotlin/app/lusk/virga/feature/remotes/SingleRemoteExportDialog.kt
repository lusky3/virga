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

private enum class SingleExportMethod { RAW, REDACTED }

/** Holds single-export method state — keeps composable param count low. */
private class SingleExportState {
    var method by mutableStateOf(SingleExportMethod.RAW)
}

/**
 * Export dialog for a single remote. Offers Raw or Redacted (secrets replaced with placeholders).
 *
 * [remoteName] is shown in the title.
 * [onConfirmRaw] — export plaintext of this remote's section.
 * [onConfirmRedacted] — export with sensitive values masked.
 * [onDismiss] — cancel.
 */
@Composable
internal fun SingleRemoteExportDialog(
    remoteName: String,
    onConfirmRaw: () -> Unit,
    onConfirmRedacted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = remember { SingleExportState() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remotes_single_export_title, remoteName)) },
        text = {
            Column {
                Text(
                    text = if (state.method == SingleExportMethod.REDACTED) {
                        stringResource(R.string.remotes_single_export_redacted_info)
                    } else {
                        stringResource(R.string.remotes_export_raw_warning)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SingleExportMethodSelector(state = state)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (state.method == SingleExportMethod.REDACTED) onConfirmRedacted()
                    else onConfirmRaw()
                },
            ) { Text(stringResource(R.string.remotes_export_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.remotes_delete_cancel)) }
        },
    )
}

@Composable
private fun SingleExportMethodSelector(state: SingleExportState) {
    Column(Modifier.selectableGroup()) {
        SingleExportRow(
            label = stringResource(R.string.remotes_single_export_raw_label),
            selected = state.method == SingleExportMethod.RAW,
            onClick = { state.method = SingleExportMethod.RAW },
        )
        SingleExportRow(
            label = stringResource(R.string.remotes_single_export_redacted_label),
            selected = state.method == SingleExportMethod.REDACTED,
            onClick = { state.method = SingleExportMethod.REDACTED },
        )
    }
}

@Composable
private fun SingleExportRow(label: String, selected: Boolean, onClick: () -> Unit) {
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
