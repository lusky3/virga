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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private enum class ExportMethod { ENCRYPTED, RAW, REDACTED }

/** Holds the encrypted-export passphrase fields so callers pass one object, not many. */
private class ExportPassphraseState {
    var passphrase by mutableStateOf("")
    var confirm by mutableStateOf("")
    var show by mutableStateOf(false)

    val mismatch: Boolean
        get() = passphrase.isNotEmpty() && confirm.isNotEmpty() && passphrase != confirm
    val canConfirm: Boolean
        get() = passphrase.isNotBlank() && passphrase == confirm
}

/**
 * Replaces the old single-button export-confirm dialog. Presents two choices:
 *
 * - **Encrypted (passphrase)**: a passphrase + confirm field; both must be non-blank
 *   and identical before Export is enabled.
 * - **Raw (unencrypted) — plaintext**: preserves the existing "anyone with this file…"
 *   warning; calls [onConfirmRaw] to use the unchanged plaintext path.
 *
 * [onConfirmEncrypted] receives a freshly-allocated [CharArray]; the caller MUST zero
 * it after use. [onDismiss] is invoked on any cancellation.
 */
@Composable
internal fun ExportConfigDialog(
    onConfirmEncrypted: (passphrase: CharArray) -> Unit,
    onConfirmRaw: () -> Unit,
    onConfirmRedacted: () -> Unit,
    onDismiss: () -> Unit,
) {
    var method by remember { mutableStateOf(ExportMethod.ENCRYPTED) }
    val state = remember { ExportPassphraseState() }
    val error = if (method == ExportMethod.ENCRYPTED && state.mismatch) {
        stringResource(R.string.remotes_export_passphrase_mismatch)
    } else {
        null
    }
    val canConfirm = method == ExportMethod.RAW || method == ExportMethod.REDACTED || state.canConfirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remotes_export_dialog_title)) },
        text = {
            Column {
                ExportMethodSelector(method = method, onSelect = { method = it })
                Spacer(Modifier.height(8.dp))
                ExportMethodFields(method = method, state = state, errorText = error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    when (method) {
                        ExportMethod.ENCRYPTED -> onConfirmEncrypted(state.passphrase.toCharArray())
                        ExportMethod.RAW -> onConfirmRaw()
                        ExportMethod.REDACTED -> onConfirmRedacted()
                    }
                },
            ) { Text(stringResource(R.string.remotes_export_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remotes_delete_cancel))
            }
        },
    )
}

/** Encrypted choice → passphrase + confirm fields; raw/redacted → descriptive text. */
@Composable
private fun ExportMethodFields(method: ExportMethod, state: ExportPassphraseState, errorText: String?) {
    if (method == ExportMethod.RAW) {
        Text(
            text = stringResource(R.string.remotes_export_raw_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (method == ExportMethod.REDACTED) {
        Text(
            text = stringResource(R.string.remotes_export_redacted_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    PassphraseField(
        value = state.passphrase,
        onValueChange = { state.passphrase = it },
        label = stringResource(R.string.remotes_export_passphrase_label),
        show = state.show,
        onToggleShow = { state.show = !state.show },
    )
    Spacer(Modifier.height(4.dp))
    PassphraseField(
        value = state.confirm,
        onValueChange = { state.confirm = it },
        label = stringResource(R.string.remotes_export_passphrase_confirm_label),
        show = state.show,
    )
    if (errorText != null) {
        Text(
            text = errorText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * A masked passphrase text field. [onToggleShow] non-null adds the show/hide eye
 * toggle (used by the primary field; the confirm field omits it and follows the
 * primary's [show]). Mismatch/error messaging is rendered by the caller.
 */
@Composable
private fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    show: Boolean,
    onToggleShow: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = onToggleShow?.let { toggle -> { ShowHideToggle(show = show, onToggle = toggle) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Eye icon button toggling passphrase visibility, with a Show/Hide a11y label. */
@Composable
private fun ShowHideToggle(show: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(
                if (show) R.string.remotes_crypt_hide_password
                else R.string.remotes_crypt_show_password,
            ),
        )
    }
}

/** The Encrypted/Raw/Redacted radio group at the top of [ExportConfigDialog]. */
@Composable
private fun ExportMethodSelector(method: ExportMethod, onSelect: (ExportMethod) -> Unit) {
    Column(Modifier.selectableGroup()) {
        ExportMethodRow(
            label = stringResource(R.string.remotes_export_encrypted_label),
            selected = method == ExportMethod.ENCRYPTED,
            onClick = { onSelect(ExportMethod.ENCRYPTED) },
        )
        ExportMethodRow(
            label = stringResource(R.string.remotes_export_raw_label),
            selected = method == ExportMethod.RAW,
            onClick = { onSelect(ExportMethod.RAW) },
        )
        ExportMethodRow(
            label = stringResource(R.string.remotes_export_redacted_label),
            selected = method == ExportMethod.REDACTED,
            onClick = { onSelect(ExportMethod.REDACTED) },
        )
    }
}

@Composable
private fun ExportMethodRow(label: String, selected: Boolean, onClick: () -> Unit) {
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

/**
 * Shown after the user confirms the wipe-warning ([ImportConfirmDialog]) when the
 * selected file is an encrypted container. A single masked passphrase field; submit
 * attempts decryption, dismiss cancels without importing.
 *
 * [onConfirm] receives a freshly-allocated [CharArray]; the caller MUST zero it.
 * Wrong-passphrase feedback arrives via the screen-level snackbar — this dialog
 * stays open so the user can retry.
 */
@Composable
internal fun ImportPassphraseDialog(
    onConfirm: (passphrase: CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remotes_import_passphrase_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.remotes_import_passphrase_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                PassphraseField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.remotes_import_passphrase_label),
                    show = show,
                    onToggleShow = { show = !show },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = passphrase.isNotBlank(),
                onClick = { onConfirm(passphrase.toCharArray()) },
            ) { Text(stringResource(R.string.remotes_import_passphrase_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remotes_delete_cancel))
            }
        },
    )
}
