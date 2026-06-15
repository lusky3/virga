package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * Dialog for renaming a remote. Validates the new name inline before calling
 * [onConfirm]. [inFlight] disables the confirm button while the rename is running.
 * 4 parameters — within Codacy's composable limit.
 */
@Composable
internal fun RenameRemoteDialog(
    oldName: String,
    inFlight: Boolean,
    onConfirm: (newName: String, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var inlineError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text(stringResource(R.string.remotes_rename_dialog_title, oldName)) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it; inlineError = null },
                label = { Text(stringResource(R.string.remotes_rename_new_name_label)) },
                singleLine = true,
                isError = inlineError != null,
                supportingText = inlineError?.let { err -> { Text(err) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !inFlight,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newName) { inlineError = it } },
                enabled = !inFlight,
            ) { Text(stringResource(R.string.remotes_rename_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) {
                Text(stringResource(R.string.remotes_delete_cancel))
            }
        },
    )
}
