package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import app.lusk.virga.core.common.model.RemoteOption
import app.lusk.virga.core.common.model.RemoteProvider
import app.lusk.virga.core.rclone.PickerEntry
import app.lusk.virga.core.rclone.SetupKind
import app.lusk.virga.core.rclone.oauth.OAuthProvider
import app.lusk.virga.core.rclone.oauth.OAuthProviders

private sealed interface AddStep {
    data object Picker : AddStep
    data class CredentialForm(val type: String, val description: String, val isOAuthByok: Boolean = false) : AddStep
    data class WrapperPlaceholder(val type: String) : AddStep
}
/**
 * [AddRemoteDialog] manual section: renders typed fields for each [RemoteOption] in
 * [options], or falls back to the freeform textarea when [options] is null.
 *
 * When the user selects the "crypt" backend type, the generic fields are replaced
 * with a dedicated [CryptRemoteForm]: a base-remote picker, optional path, and
 * masked password fields. The OAuth chips and BYO keys section are hidden for crypt
 * because they are irrelevant.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AddRemoteDialog(
    oauthProviders: List<OAuthProvider>,
    error: String?,
    customClientIds: Map<String, String>,
    /**
     * Called once when the dialog opens so the VM can start loading provider schema.
     * Safe to call repeatedly — the VM guards against double-loading.
     */
    onEnsureProviders: () -> Unit,
    /**
     * Returns the full option list (basic + advanced) for a backend type, or null
     * when the schema is not yet loaded or the type is unknown → fall back to freeform.
     */
    allOptionsForBackend: (String) -> List<RemoteOption>?,
    /**
     * The loaded provider schema (null until loaded). Passed in only so the typed
     * fields recompute when the schema arrives AFTER the user already picked a
     * type — [optionsForBackend] reads a non-observable StateFlow.value, so we
     * key the lookup on this Compose-observable value.
     */
    providersLoaded: List<RemoteProvider>?,
    /** Picker entries from the loaded ProviderCatalog, or null when not yet ready. */
    pickerEntries: List<PickerEntry>? = null,
    /** Classifies a backend type for routing. */
    setupKindFor: (String) -> SetupKind = { SetupKind.Credential },
    /** All currently configured remotes — used by [CryptRemoteForm] to populate the base picker. */
    existingRemotes: List<Remote> = emptyList(),
    oauthInProgress: Boolean = false,
    onDismiss: () -> Unit,
    onManualConfirm: (name: String, type: String, params: String) -> Unit,
    onCryptConfirm: (name: String, baseRemote: String, basePath: String, password: String, salt: String) -> Unit = { _, _, _, _, _ -> },
    onWrapperConfirm: (name: String, type: String, params: String) -> Unit = { _, _, _ -> },
    onOAuth: (provider: OAuthProvider, name: String) -> Unit,
    onDaemonOAuth: (type: String, name: String, clientId: String?, clientSecret: String?) -> Unit = { _, _, _, _ -> },
    onCancelDaemonOAuth: () -> Unit = {},
    onSaveClientId: (providerId: String, clientId: String) -> Unit,
    onClearClientId: (providerId: String) -> Unit,
) {
    LaunchedEffect(Unit) { onEnsureProviders() }

    var name by rememberSaveable { mutableStateOf("") }
    // rclone permits a broad set of remote-name characters (letters, digits, spaces,
    // _ . - +, …) — only ':' and '/' are genuinely dangerous, as they corrupt the
    // "remote:path" spec downstream. Validate by EXCLUDING just those two rather than
    // an allow-list, so legitimate names like "My Drive" aren't rejected. Blank is
    // "not yet invalid" (no error until typed).
    val nameValid = name.isBlank() || name.trim().none { it == ':' || it == '/' }
    val nameUsable = name.isNotBlank() && nameValid
    var type by rememberSaveable { mutableStateOf("") }
    var params by remember { mutableStateOf("") }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    // Step state: when the picker is available, start in Picker mode.
    // Note: step is `remember` (not rememberSaveable) because AddStep is a sealed class
    // and would require a custom Saver. On rotation the dialog resets to the Picker step.
    var step by remember { mutableStateOf<AddStep>(AddStep.Picker) }

    val isCrypt = type.trim().equals("crypt", ignoreCase = true)

    // Crypt wizard state — only used when isCrypt is true.
    var cryptBaseRemote by remember { mutableStateOf("") }
    var cryptBasePath by remember { mutableStateOf("") }
    var cryptPassword by remember { mutableStateOf("") }
    var cryptSalt by remember { mutableStateOf("") }

    // Reset crypt fields when the user switches away from crypt.
    LaunchedEffect(isCrypt) {
        if (!isCrypt) {
            cryptBaseRemote = ""
            cryptBasePath = ""
            cryptPassword = ""
            cryptSalt = ""
        }
    }

    // Wrapper sub-flow state (non-crypt wrappers)
    var wrapperSelectedRemote by remember { mutableStateOf("") }
    var wrapperSelectedRemotes by remember { mutableStateOf(emptySet<String>()) }
    val wrapperTypedValues = remember { mutableStateMapOf<String, String>() }
    var wrapperShowAdvanced by remember { mutableStateOf(false) }

    // Reset wrapper state when step changes away from wrapper
    LaunchedEffect(step) {
        if (step !is AddStep.WrapperPlaceholder) {
            wrapperSelectedRemote = ""
            wrapperSelectedRemotes = emptySet()
            wrapperTypedValues.clear()
            wrapperShowAdvanced = false
        }
    }

    val filteredBackends = remember(type) {
        val q = type.trim().lowercase()
        if (q.isEmpty()) RcloneBackendTypes
        else RcloneBackendTypes.filter { (id, label) ->
            id.contains(q) || label.lowercase().contains(q)
        }
    }

    // Typed field values keyed by option name. Reset when the backend type changes.
    val typedValues = remember { mutableStateMapOf<String, String>() }
    var showAdvanced by remember { mutableStateOf(false) }

    // Recompute which options to render whenever type changes.
    val schemaOptions: List<RemoteOption>? = remember(type, providersLoaded) { allOptionsForBackend(type.trim()) }

    // Reset typed values when the user picks a different backend type.
    LaunchedEffect(type) {
        typedValues.clear()
        showAdvanced = false
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VirgaSpacing.lg, vertical = VirgaSpacing.sm)
                .padding(bottom = VirgaSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
        ) {
            Text(
                stringResource(R.string.remotes_add_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = VirgaSpacing.sm),
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.remotes_add_field_name)) },
                isError = !nameValid,
                supportingText = if (!nameValid) {
                    { Text(stringResource(R.string.remotes_add_name_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            when {
                // Step: Picker — show the provider picker when available
                step == AddStep.Picker && pickerEntries != null -> {
                    ProviderPicker(
                        entries = pickerEntries,
                        setupKindFor = setupKindFor,
                        onSelect = { entry ->
                            type = entry.type
                            when (val kind = setupKindFor(entry.type)) {
                                is SetupKind.OAuth -> {
                                    if (kind.bundled) {
                                        // Find matching OAuthProvider and fire OAuth flow
                                        val provider = OAuthProviders.All.firstOrNull { it.type == entry.type }
                                        if (provider != null) {
                                            onOAuth(provider, name)
                                        } else {
                                            step = AddStep.CredentialForm(entry.type, entry.description)
                                        }
                                    } else {
                                        step = AddStep.CredentialForm(entry.type, entry.description, isOAuthByok = true)
                                    }
                                }
                                SetupKind.Credential -> {
                                    step = AddStep.CredentialForm(entry.type, entry.description)
                                }
                                SetupKind.Wrapper -> {
                                    step = AddStep.WrapperPlaceholder(entry.type)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Step: Wrapper sub-flow
                step is AddStep.WrapperPlaceholder -> {
                    val wrapperType = (step as AddStep.WrapperPlaceholder).type
                    val isWrapperCrypt = wrapperType.equals("crypt", ignoreCase = true)
                    val isUnion = wrapperType.equals("union", ignoreCase = true)
                    val wrapperOptions: List<RemoteOption>? = remember(wrapperType, providersLoaded) {
                        allOptionsForBackend(wrapperType)
                    }

                    WrapperForm(
                        wrapperType = wrapperType,
                        existingRemotes = existingRemotes,
                        schemaOptions = wrapperOptions,
                        selectedRemote = wrapperSelectedRemote,
                        onRemoteSelected = { wrapperSelectedRemote = it },
                        selectedRemotes = wrapperSelectedRemotes,
                        onRemotesChanged = { wrapperSelectedRemotes = it },
                        typedValues = wrapperTypedValues,
                        showAdvanced = wrapperShowAdvanced,
                        onToggleAdvanced = { wrapperShowAdvanced = !wrapperShowAdvanced },
                        cryptBaseRemote = cryptBaseRemote,
                        onCryptBaseRemoteSelected = { cryptBaseRemote = it },
                        cryptBasePath = cryptBasePath,
                        onCryptBasePathChange = { cryptBasePath = it },
                        cryptPassword = cryptPassword,
                        onCryptPasswordChange = { cryptPassword = it },
                        cryptSalt = cryptSalt,
                        onCryptSaltChange = { cryptSalt = it },
                    )

                    if (error != null) {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { step = AddStep.Picker; type = "" }) {
                            Text(stringResource(R.string.remotes_add_cancel))
                        }
                        TextButton(
                            onClick = {
                                if (isWrapperCrypt) {
                                    onCryptConfirm(name.trim(), cryptBaseRemote, cryptBasePath, cryptPassword, cryptSalt)
                                } else {
                                    val paramsText = buildWrapperParams(
                                        wrapperType = wrapperType,
                                        isUnion = isUnion,
                                        selectedRemote = wrapperSelectedRemote,
                                        selectedRemotes = wrapperSelectedRemotes,
                                        typedValues = wrapperTypedValues,
                                    )
                                    onWrapperConfirm(name.trim(), wrapperType, paramsText)
                                }
                            },
                            enabled = nameUsable && when {
                                isWrapperCrypt -> cryptBaseRemote.isNotBlank() && cryptPassword.isNotBlank()
                                isUnion -> wrapperSelectedRemotes.isNotEmpty()
                                else -> wrapperSelectedRemote.isNotBlank()
                            },
                        ) { Text(stringResource(R.string.remotes_add_create)) }
                    }
                }

                // Step: CredentialForm or legacy fallback (pickerEntries == null)
                else -> {
                    // OAuth sign-in chips and BYO keys are irrelevant for crypt remotes.
                    if (!isCrypt && pickerEntries == null) {
                        Text(
                            stringResource(R.string.remotes_add_sign_in_with),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
                            oauthProviders.forEach { provider ->
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
                            providers = oauthProviders,
                            customClientIds = customClientIds,
                            onSaveClientId = onSaveClientId,
                            onClearClientId = onClearClientId,
                        )

                        HorizontalDivider()
                        Text(
                            stringResource(R.string.remotes_add_or_manual),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    // Show legacy dropdown only when pickerEntries is null (fallback)
                    if (pickerEntries == null) {
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
                                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                                    Text(
                                                        id,
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
                    }

                    // For non-bundled OAuth providers, show the daemon OAuth form instead of
                    // the old "use rclone authorize" hint. The form handles credential input,
                    // progress, and cancellation.
                    if ((step as? AddStep.CredentialForm)?.isOAuthByok == true) {
                        DaemonOAuthForm(
                            providerName = type.trim(),
                            oauthInProgress = oauthInProgress,
                            onConnect = { clientId, clientSecret ->
                                onDaemonOAuth(
                                    type.trim(),
                                    name.trim(),
                                    clientId.ifBlank { null },
                                    clientSecret.ifBlank { null },
                                )
                            },
                            onCancel = onCancelDaemonOAuth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        return@Column
                    }

                    // Crypt wizard replaces generic typed / freeform fields when type == "crypt".
                    if (isCrypt) {
                        CryptRemoteForm(
                            existingRemotes = existingRemotes,
                            selectedBaseRemote = cryptBaseRemote,
                            onBaseRemoteSelected = { cryptBaseRemote = it },
                            basePath = cryptBasePath,
                            onBasePathChange = { cryptBasePath = it },
                            password = cryptPassword,
                            onPasswordChange = { cryptPassword = it },
                            salt = cryptSalt,
                            onSaltChange = { cryptSalt = it },
                        )
                    } else if (schemaOptions != null) {
                        TypedOptionFields(
                            options = schemaOptions,
                            values = typedValues,
                            showAdvanced = showAdvanced,
                            onToggleAdvanced = { showAdvanced = !showAdvanced },
                        )
                    } else {
                        OutlinedTextField(
                            value = params,
                            onValueChange = { params = it },
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
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (pickerEntries != null) {
                            TextButton(onClick = { step = AddStep.Picker; type = "" }) {
                                Text(stringResource(R.string.remotes_add_cancel))
                            }
                        } else {
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.remotes_add_cancel)) }
                        }
                        TextButton(
                            onClick = {
                                if (isCrypt) {
                                    onCryptConfirm(name.trim(), cryptBaseRemote, cryptBasePath, cryptPassword, cryptSalt)
                                } else {
                                    val paramsText = if (schemaOptions != null) {
                                        typedValues.entries
                                            .filter { (_, v) -> v.isNotBlank() }
                                            .joinToString("\n") { (k, v) -> "$k=$v" }
                                    } else {
                                        params
                                    }
                                    onManualConfirm(name, type, paramsText)
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
        }
    }
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
            Row(horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
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

/**
 * Builds the key=value parameter text for a wrapper remote.
 * Union uses `upstreams = remote1: remote2:`, others use `remote = name:`.
 */
private fun buildWrapperParams(
    wrapperType: String,
    isUnion: Boolean,
    selectedRemote: String,
    selectedRemotes: Set<String>,
    typedValues: Map<String, String>,
): String {
    val baseParam = if (isUnion) {
        "upstreams=" + selectedRemotes.joinToString(" ") { "$it:" }
    } else {
        "remote=$selectedRemote:"
    }
    val extras = typedValues.entries
        .filter { (_, v) -> v.isNotBlank() }
        .joinToString("\n") { (k, v) -> "$k=$v" }
    return if (extras.isBlank()) baseParam else "$baseParam\n$extras"
}

/**
 * Form shown when the user picks a non-bundled OAuth provider (bundled=false).
 * Lets them optionally provide their own client credentials, then starts the
 * daemon-mediated OAuth flow via [onConnect].
 */
@Composable
private fun DaemonOAuthForm(
    providerName: String,
    oauthInProgress: Boolean,
    onConnect: (clientId: String, clientSecret: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var clientId by rememberSaveable { mutableStateOf("") }
    var clientSecret by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        Text(
            text = stringResource(R.string.remotes_daemon_oauth_title, providerName),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.remotes_daemon_oauth_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text(stringResource(R.string.remotes_daemon_oauth_client_id)) },
            singleLine = true,
            enabled = !oauthInProgress,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            label = { Text(stringResource(R.string.remotes_daemon_oauth_client_secret)) },
            singleLine = true,
            enabled = !oauthInProgress,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        if (oauthInProgress) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.remotes_daemon_oauth_waiting), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.remotes_daemon_oauth_cancel)) }
            }
        } else {
            Button(
                onClick = { onConnect(clientId, clientSecret) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.remotes_daemon_oauth_connect))
            }
        }
    }
}
