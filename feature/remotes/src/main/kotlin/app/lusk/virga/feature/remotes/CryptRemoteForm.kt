package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.lusk.virga.core.common.model.Remote
/**
 * Crypt-specific form fields shown inside [AddRemoteDialog] when the user selects
 * the "crypt" backend type. Presents:
 *  - A dropdown to pick the base remote from existing configured remotes.
 *  - An optional path field for the subfolder on the base remote.
 *  - A password field with show/hide toggle (no plaintext ever leaves this composable).
 *  - An optional salt / password2 field with the same masking.
 *  - A warning that the password cannot be recovered.
 *
 * No password value is logged or persisted here. The caller forwards
 * the values directly to the ViewModel which delegates to [RcloneEngine.createCryptRemote].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CryptRemoteForm(
    existingRemotes: List<Remote>,
    selectedBaseRemote: String,
    onBaseRemoteSelected: (String) -> Unit,
    basePath: String,
    onBasePathChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    salt: String,
    onSaltChange: (String) -> Unit,
) {
    var baseMenuExpanded by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var showSalt by remember { mutableStateOf(false) }

    if (existingRemotes.isEmpty()) {
        Text(
            stringResource(R.string.remotes_crypt_no_base_remotes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    Text(
        stringResource(R.string.remotes_crypt_title),
        style = MaterialTheme.typography.titleSmall,
    )

    ExposedDropdownMenuBox(
        expanded = baseMenuExpanded,
        onExpandedChange = { baseMenuExpanded = it },
    ) {
        OutlinedTextField(
            value = selectedBaseRemote,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.remotes_crypt_base_remote_label)) },
            placeholder = { Text(stringResource(R.string.remotes_crypt_base_remote_placeholder)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = baseMenuExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = baseMenuExpanded,
            onDismissRequest = { baseMenuExpanded = false },
        ) {
            existingRemotes.forEach { remote ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(remote.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                remote.type,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onBaseRemoteSelected(remote.name)
                        baseMenuExpanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }

    OutlinedTextField(
        value = basePath,
        onValueChange = onBasePathChange,
        label = { Text(stringResource(R.string.remotes_crypt_base_path_label)) },
        placeholder = { Text(stringResource(R.string.remotes_crypt_base_path_placeholder)) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.remotes_crypt_password_label)) },
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (showPassword) R.string.remotes_crypt_hide_password
                        else R.string.remotes_crypt_show_password,
                    ),
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = salt,
        onValueChange = onSaltChange,
        label = { Text(stringResource(R.string.remotes_crypt_salt_label)) },
        visualTransformation = if (showSalt) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showSalt = !showSalt }) {
                Icon(
                    imageVector = if (showSalt) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (showSalt) R.string.remotes_crypt_hide_password
                        else R.string.remotes_crypt_show_password,
                    ),
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        stringResource(R.string.remotes_crypt_password_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}
