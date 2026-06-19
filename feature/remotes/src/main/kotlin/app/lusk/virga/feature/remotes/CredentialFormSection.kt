package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteOption
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import app.lusk.virga.core.rclone.oauth.OAuthProvider
import app.lusk.virga.core.rclone.oauth.OAuthProviders

/**
 * The credential-form step of [AddRemoteDialog]: typed schema fields (or the
 * freeform textarea fallback), the crypt wizard, the daemon-OAuth form for
 * non-bundled OAuth providers, and — when the provider picker is unavailable
 * ([usePicker] is false) — the legacy OAuth chips / BYO keys / type dropdown.
 *
 * All form state stays hoisted in [AddRemoteDialog] (it is shared with the
 * wrapper step and survives navigating back to the picker); this composable
 * only renders it. Extracted verbatim to keep the dialog under the file-size
 * budget.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CredentialFormSection(
    name: String,
    nameUsable: Boolean,
    type: String,
    onTypeChange: (String) -> Unit,
    isOAuthByok: Boolean,
    isCrypt: Boolean,
    usePicker: Boolean,
    schemaOptions: List<RemoteOption>?,
    typedValues: MutableMap<String, String>,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    params: String,
    onParamsChange: (String) -> Unit,
    oauthProviders: List<OAuthProvider>,
    customClientIds: Map<String, String>,
    existingRemotes: List<Remote>,
    error: String?,
    oauthInProgress: Boolean,
    daemonOAuthTokenPrompt: String?,
    daemonOAuthFieldPrompt: DaemonOAuthFieldPrompt?,
    cryptBaseRemote: String,
    onCryptBaseRemoteSelected: (String) -> Unit,
    cryptBasePath: String,
    onCryptBasePathChange: (String) -> Unit,
    cryptPassword: String,
    onCryptPasswordChange: (String) -> Unit,
    cryptSalt: String,
    onCryptSaltChange: (String) -> Unit,
    onEnsureProviders: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onOAuth: (provider: OAuthProvider, name: String) -> Unit,
    onManualConfirm: (name: String, type: String, params: Map<String, String>) -> Unit,
    onCryptConfirm: (name: String, baseRemote: String, basePath: String, password: String, salt: String) -> Unit,
    onDaemonOAuth: (type: String, name: String, clientId: String?, clientSecret: String?) -> Unit,
    /** Secondary action: restart the flow with paste-token fallback (forcePasteToken=true). */
    onDaemonOAuthDesktop: (type: String, name: String, clientId: String?, clientSecret: String?) -> Unit = { _, _, _, _ -> },
    onSubmitDaemonOAuthToken: (String) -> Unit,
    onSubmitDaemonOAuthFieldAnswer: (String) -> Unit,
    onCancelDaemonOAuth: () -> Unit,
    onSaveClientId: (providerId: String, clientId: String) -> Unit,
    onClearClientId: (providerId: String) -> Unit,
    onSaveClientSecret: (providerId: String, secret: String) -> Unit = { _, _ -> },
    onClearClientSecret: (providerId: String) -> Unit = { _ -> },
) {
    var typeMenuExpanded by remember { mutableStateOf(false) }

    val filteredBackends = remember(type) {
        val q = type.trim().lowercase()
        if (q.isEmpty()) RcloneBackendTypes
        else RcloneBackendTypes.filter { (id, label) ->
            id.contains(q) || label.lowercase().contains(q)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
    ) {
        // OAuth sign-in chips and BYO keys are irrelevant for crypt remotes.
        if (!isCrypt && !usePicker) {
            Text(
                stringResource(R.string.remotes_add_sign_in_with),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
                oauthProviders.forEach { provider ->
                    val custom = provider.id in customClientIds
                    AssistChip(
                        onClick = { onOAuth(provider, name) },
                        enabled = nameUsable,
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
                providers = oauthProviders,
                customClientIds = customClientIds,
                onSaveClientId = onSaveClientId,
                onClearClientId = onClearClientId,
                onSaveClientSecret = onSaveClientSecret,
                onClearClientSecret = onClearClientSecret,
            )

            HorizontalDivider()
            Text(
                stringResource(R.string.remotes_add_or_manual),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // Show legacy dropdown only when the picker is unavailable (fallback)
        if (!usePicker) {
            ExposedDropdownMenuBox(
                expanded = typeMenuExpanded,
                onExpandedChange = { typeMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = type,
                    onValueChange = { onTypeChange(it); typeMenuExpanded = true },
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
                                        Text(label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            id,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    onTypeChange(id)
                                    typeMenuExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
            }
        }

        // For non-bundled OAuth providers, show the daemon OAuth form instead of
        // the old "use rclone authorize" hint. The form handles credential input,
        // the paste-token stage, progress, and cancellation.
        if (isOAuthByok) {
            DaemonOAuthForm(
                providerName = type.trim(),
                nameUsable = nameUsable,
                oauthInProgress = oauthInProgress,
                tokenPrompt = daemonOAuthTokenPrompt,
                fieldPrompt = daemonOAuthFieldPrompt,
                onConnect = { clientId, clientSecret ->
                    onDaemonOAuth(
                        type.trim(),
                        name.trim(),
                        clientId.ifBlank { null },
                        clientSecret.ifBlank { null },
                    )
                },
                onSubmitToken = onSubmitDaemonOAuthToken,
                onSubmitFieldAnswer = onSubmitDaemonOAuthFieldAnswer,
                onCancel = onCancelDaemonOAuth,
                onUseDesktopAuth = { clientId, clientSecret ->
                    onDaemonOAuthDesktop(
                        type.trim(),
                        name.trim(),
                        clientId.ifBlank { null },
                        clientSecret.ifBlank { null },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }

        // Crypt wizard replaces generic typed / freeform fields when type == "crypt".
        if (isCrypt) {
            CryptRemoteForm(
                existingRemotes = existingRemotes,
                selectedBaseRemote = cryptBaseRemote,
                onBaseRemoteSelected = onCryptBaseRemoteSelected,
                basePath = cryptBasePath,
                onBasePathChange = onCryptBasePathChange,
                password = cryptPassword,
                onPasswordChange = onCryptPasswordChange,
                salt = cryptSalt,
                onSaltChange = onCryptSaltChange,
            )
        } else if (schemaOptions != null) {
            TypedOptionFields(
                options = schemaOptions,
                values = typedValues,
                showAdvanced = showAdvanced,
                onToggleAdvanced = onToggleAdvanced,
                backendType = type.trim().ifEmpty { null },
            )
        } else {
            OutlinedTextField(
                value = params,
                onValueChange = onParamsChange,
                label = { Text(stringResource(R.string.remotes_add_field_params)) },
                placeholder = { Text(stringResource(R.string.remotes_add_params_placeholder)) },
                supportingText = { Text(stringResource(R.string.remotes_add_params_supporting)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onEnsureProviders) {
                Text(stringResource(R.string.remotes_retry))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (usePicker) {
                TextButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    Text(stringResource(R.string.remotes_back))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.remotes_add_cancel)) }
            }
            TextButton(
                onClick = {
                    if (isCrypt) {
                        onCryptConfirm(name.trim(), cryptBaseRemote, cryptBasePath, cryptPassword, cryptSalt)
                    } else {
                        val paramsMap: Map<String, String> = if (schemaOptions != null) {
                            // Pass the typed-values map directly — no newline serialisation so
                            // multi-line values (e.g. SFTP key_pem) are preserved intact.
                            typedValues.entries
                                .filter { (_, v) -> v.isNotBlank() }
                                .associate { (k, v) -> k to v }
                        } else {
                            // Freeform textarea: parse "key=value" lines into a map.
                            params.lines().mapNotNull { line ->
                                val idx = line.indexOf('=')
                                if (idx <= 0) null
                                else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                            }.toMap()
                        }
                        onManualConfirm(name, type, paramsMap)
                    }
                },
                enabled = if (isCrypt) {
                    nameUsable && cryptBaseRemote.isNotBlank() && cryptPassword.isNotBlank()
                } else {
                    val formValid = schemaOptions
                        ?.filter { it.required }
                        ?.all { opt ->
                            val value = typedValues[opt.name] ?: opt.default.orEmpty()
                            value.isNotBlank()
                        } ?: true
                    nameUsable && type.isNotBlank() && formValid
                },
            ) { Text(stringResource(R.string.remotes_add_create)) }
        }
    }
}

/**
 * Expandable "bring your own OAuth keys" section. The built-in client IDs are
 * shared across all installs and share one app's rate limits; power users paste
 * their own client ID here (Virga uses PKCE for most providers, so no client
 * secret is needed for those). For Google Drive, a client secret enables the
 * rclone daemon flow, bypassing the manifest redirect constraint (UI-M1).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ByoKeysSection(
    providers: List<OAuthProvider>,
    customClientIds: Map<String, String>,
    onSaveClientId: (providerId: String, clientId: String) -> Unit,
    onClearClientId: (providerId: String) -> Unit,
    onSaveClientSecret: (providerId: String, secret: String) -> Unit,
    onClearClientSecret: (providerId: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf(providers.firstOrNull()?.id.orEmpty()) }
    // Field text tracks the selected provider's stored value; editing overrides it.
    var clientIdText by remember(selectedId) { mutableStateOf(customClientIds[selectedId].orEmpty()) }
    // Secret is sensitive: kept in plain remember (never saved to state Bundle).
    var clientSecretText by remember(selectedId) { mutableStateOf("") }
    val showSecretField = selectedId == OAuthProviders.GoogleDrive.id

    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(R.string.remotes_byo_toggle))
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
            Text(
                stringResource(R.string.remotes_byo_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
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
            if (showSecretField) {
                OutlinedTextField(
                    value = clientSecretText,
                    onValueChange = { clientSecretText = it },
                    label = { Text(stringResource(R.string.remotes_byo_client_secret)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
                TextButton(
                    onClick = {
                        onSaveClientId(selectedId, clientIdText)
                        // Only forward a non-blank secret: a blank field means "leave the stored
                        // secret alone" (setClientSecret treats blank as removal), so saving after
                        // editing only the client ID must not silently wipe the secret.
                        if (showSecretField && clientSecretText.isNotBlank()) {
                            onSaveClientSecret(selectedId, clientSecretText)
                        }
                    },
                    enabled = selectedId.isNotBlank(),
                ) { Text(stringResource(R.string.remotes_byo_save)) }
                if (selectedId in customClientIds) {
                    TextButton(
                        onClick = {
                            onClearClientId(selectedId)
                            clientIdText = ""
                            // Clear the paired secret too — a stale secret left behind would
                            // wrongly pair with a later different client ID for this provider.
                            if (showSecretField) onClearClientSecret(selectedId)
                            clientSecretText = ""
                        },
                    ) { Text(stringResource(R.string.remotes_byo_clear)) }
                }
            }
        }
    }
}
