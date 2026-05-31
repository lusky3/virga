package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.lusk.virga.core.designsystem.component.VirgaCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.rclone.oauth.OAuthProvider

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
    onCreateTask: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    VirgaCard(onClick = onOpenBrowser) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    remote.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(remote.type, style = MaterialTheme.typography.bodySmall)
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
                    onClick = { menuExpanded = false; onCreateTask() },
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AddRemoteDialog(
    providers: List<OAuthProvider>,
    error: String?,
    customClientIds: Map<String, String>,
    onDismiss: () -> Unit,
    onManualConfirm: (name: String, type: String, params: String) -> Unit,
    onOAuth: (provider: OAuthProvider, name: String) -> Unit,
    onSaveClientId: (providerId: String, clientId: String) -> Unit,
    onClearClientId: (providerId: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var params by remember { mutableStateOf("") }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    val filteredBackends = remember(type) {
        val q = type.trim().lowercase()
        if (q.isEmpty()) RcloneBackendTypes
        else RcloneBackendTypes.filter { (id, label) ->
            id.contains(q) || label.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remotes_add_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.remotes_add_field_name)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    stringResource(R.string.remotes_add_sign_in_with),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    providers.forEach { provider ->
                        val custom = provider.id in customClientIds
                        AssistChip(
                            onClick = { onOAuth(provider, name) },
                            enabled = name.isNotBlank(),
                            label = {
                                Text(
                                    if (custom) stringResource(R.string.remotes_byo_chip_custom, provider.displayName)
                                    else provider.displayName,
                                )
                            },
                        )
                    }
                }

                ByoKeysSection(
                    providers = providers,
                    customClientIds = customClientIds,
                    onSaveClientId = onSaveClientId,
                    onClearClientId = onClearClientId,
                )

                HorizontalDivider()
                Text(
                    stringResource(R.string.remotes_add_or_manual),
                    style = MaterialTheme.typography.labelLarge,
                )

                // Searchable / editable dropdown for backend type
                ExposedDropdownMenuBox(
                    expanded = typeMenuExpanded,
                    onExpandedChange = { typeMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = type,
                        onValueChange = { type = it; typeMenuExpanded = true },
                        label = { Text(stringResource(R.string.remotes_add_field_type)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded)
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                    )
                    if (filteredBackends.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = typeMenuExpanded,
                            onDismissRequest = { typeMenuExpanded = false },
                        ) {
                            filteredBackends.forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(id, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        type = id
                                        typeMenuExpanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = params,
                    onValueChange = { params = it },
                    label = { Text(stringResource(R.string.remotes_add_field_params)) },
                    placeholder = { Text(stringResource(R.string.remotes_add_params_placeholder)) },
                    supportingText = { Text(stringResource(R.string.remotes_add_params_supporting)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onManualConfirm(name, type, params) },
                enabled = name.isNotBlank() && type.isNotBlank(),
            ) { Text(stringResource(R.string.remotes_add_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.remotes_add_cancel)) }
        },
    )
}

/**
 * Expandable "bring your own OAuth keys" section. The built-in client IDs are
 * shared across all installs and share one app's rate limits; power users paste
 * their own client ID here (Virga uses PKCE, so no client secret is needed).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ByoKeysSection(
    providers: List<OAuthProvider>,
    customClientIds: Map<String, String>,
    onSaveClientId: (providerId: String, clientId: String) -> Unit,
    onClearClientId: (providerId: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf(providers.firstOrNull()?.id.orEmpty()) }
    // Field text tracks the selected provider's stored value; editing overrides it.
    var clientIdText by remember(selectedId) { mutableStateOf(customClientIds[selectedId].orEmpty()) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(R.string.remotes_byo_toggle))
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.remotes_byo_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { provider ->
                    FilterChip(
                        selected = provider.id == selectedId,
                        onClick = { selectedId = provider.id },
                        label = { Text(provider.displayName) },
                    )
                }
            }
            OutlinedTextField(
                value = clientIdText,
                onValueChange = { clientIdText = it },
                label = { Text(stringResource(R.string.remotes_byo_client_id)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onSaveClientId(selectedId, clientIdText) },
                    enabled = selectedId.isNotBlank(),
                ) { Text(stringResource(R.string.remotes_byo_save)) }
                if (selectedId in customClientIds) {
                    TextButton(onClick = { onClearClientId(selectedId); clientIdText = "" }) {
                        Text(stringResource(R.string.remotes_byo_clear))
                    }
                }
            }
        }
    }
}
