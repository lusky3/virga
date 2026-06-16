package app.lusk.virga.feature.explorer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight

/**
 * Destructive confirm dialog shown before deleting selected items.
 * [count] is the number of items to be deleted — used for the title plural.
 */
@Composable
internal fun DeleteConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(pluralStringResource(R.plurals.explorer_delete_confirm_title, count, count))
        },
        text = {
            Text(stringResource(R.string.explorer_delete_confirm_body))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.explorer_delete_confirm_confirm),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * Dialog to rename a single item. [initialName] pre-fills the text field.
 * [errorRes] is a string resource for a validation error, or null when valid.
 */
@Composable
internal fun RenameDialog(
    initialName: String,
    errorRes: Int?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val confirmEnabled = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = errorRes != null,
                label = { Text(stringResource(R.string.explorer_rename_hint)) },
                supportingText = if (errorRes != null) {
                    { Text(stringResource(errorRes)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = confirmEnabled) {
                Text(stringResource(R.string.explorer_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * Dialog for the user to type a destination path for a move or copy operation.
 * [titleRes] switches the title between "Move to folder" and "Copy to folder".
 * [confirmRes] switches the confirm button label.
 */
@Composable
internal fun DestinationDialog(
    titleRes: Int,
    confirmRes: Int,
    onConfirm: (destDir: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var dest by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = dest,
                onValueChange = { dest = it },
                singleLine = true,
                label = { Text(stringResource(R.string.explorer_dest_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            // Require a non-blank entry so an accidental tap can't silently move/copy to
            // the remote root; users who want root can type "/" (it normalizes to "").
            TextButton(onClick = { onConfirm(dest.trim().trim('/')) }, enabled = dest.isNotBlank()) {
                Text(stringResource(confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
