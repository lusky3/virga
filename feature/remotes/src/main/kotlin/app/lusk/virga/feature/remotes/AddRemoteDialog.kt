package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.validation.isValidRemoteName
import app.lusk.virga.core.designsystem.back.DismissOnBack
import app.lusk.virga.core.designsystem.component.VirgaBottomSheet
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
    /** rclone's paste-token instructions while the daemon OAuth flow awaits a token; null otherwise. */
    daemonOAuthTokenPrompt: String? = null,
    /** Non-null while the daemon OAuth flow awaits a required field value with no default. */
    daemonOAuthFieldPrompt: DaemonOAuthFieldPrompt? = null,
    /** Non-null when the dialog is opened in edit mode for an existing remote. */
    editMode: EditModeState? = null,
    onDismiss: () -> Unit,
    onManualConfirm: (name: String, type: String, params: Map<String, String>) -> Unit,
    /** Called with the submitted key→value map when confirming an edit. Only used when [editMode] != null. */
    onEditConfirm: (values: Map<String, String>) -> Unit = {},
    onCryptConfirm: (name: String, baseRemote: String, basePath: String, password: String, salt: String) -> Unit = { _, _, _, _, _ -> },
    onWrapperConfirm: (name: String, type: String, params: String) -> Unit = { _, _, _ -> },
    onOAuth: (provider: OAuthProvider, name: String) -> Unit,
    onDaemonOAuth: (type: String, name: String, clientId: String?, clientSecret: String?) -> Unit = { _, _, _, _ -> },
    onSubmitDaemonOAuthToken: (String) -> Unit = {},
    onSubmitDaemonOAuthFieldAnswer: (String) -> Unit = {},
    onCancelDaemonOAuth: () -> Unit = {},
    onSaveClientId: (providerId: String, clientId: String) -> Unit,
    onClearClientId: (providerId: String) -> Unit,
    onSaveClientSecret: (providerId: String, secret: String) -> Unit = { _, _ -> },
    onClearClientSecret: (providerId: String) -> Unit = { _ -> },
) {
    LaunchedEffect(Unit) { onEnsureProviders() }

    var name by rememberSaveable { mutableStateOf("") }
    // rclone permits a broad set of remote-name characters (letters, digits, spaces,
    // _ . - +, …) — only ':' and '/' are genuinely dangerous, as they corrupt the
    // "remote:path" spec downstream. Validate by EXCLUDING just those two rather than
    // an allow-list, so legitimate names like "My Drive" aren't rejected. Blank is
    // "not yet invalid" (no error until typed).
    val nameError = when {
        name.isBlank() -> null
        name.length > 64 -> stringResource(R.string.remotes_add_name_too_long)
        name.any { it in '\u0000'..'\u001F' } -> stringResource(R.string.remotes_add_name_control_chars)
        else -> null
    }
    val nameValid = name.isBlank() || isValidRemoteName(name)
    val nameUsable = name.isNotBlank() && nameValid
    var type by rememberSaveable { mutableStateOf("") }
    var params by remember { mutableStateOf("") }

    // Note: step uses remember (not rememberSaveable) because AddStep is a sealed
    // class requiring a custom Saver. On process death the dialog resets to Picker.
    // This is acceptable: no data is lost (name/type are saveable), just navigation state.
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

    // Edit mode typed values — pre-filled from loadedParams minus password keys and "type".
    val editTypedValues = remember { mutableStateMapOf<String, String>() }
    var editShowAdvanced by remember { mutableStateOf(false) }

    // Reactive schema for the edited remote type. Recomputes when providers arrive.
    val editSchemaOptions: List<RemoteOption>? = remember(editMode, providersLoaded) {
        editMode?.let { allOptionsForBackend(it.remoteType) }
    }

    // Seed editTypedValues only once the schema is resolved — the password-key exclusion
    // set is derived REACTIVELY from editSchemaOptions (not from the frozen
    // EditModeState.passwordKeys) to prevent obscured secrets from leaking into the
    // field if the schema hadn't loaded when beginEditRemote ran.
    // Key on editSchemaOptions: fires when both editMode and the schema are available.
    LaunchedEffect(editSchemaOptions) {
        if (editMode != null && editSchemaOptions != null) {
            val reactivePasswordKeys = editSchemaOptions
                .filter { it.isPassword }
                .map { it.name }
                .toSet()
            editTypedValues.clear()
            editMode.loadedParams
                .filterKeys { k -> k != "type" && k !in reactivePasswordKeys }
                .forEach { (k, v) -> editTypedValues[k] = v }
        }
    }

    VirgaBottomSheet(onDismiss = onDismiss, scrimDescription = stringResource(R.string.remotes_sheet_dismiss)) {
        // Within-panel back: on a sub-step, Back returns to the picker rather than closing
        // the sheet. Registered after the sheet's own handler so it wins (LIFO); absent at
        // the Picker step, where Back falls through to the sheet's close.
        if (editMode == null && step != AddStep.Picker) {
            DismissOnBack { step = AddStep.Picker }
        }
        // Edit mode: bypass the normal step machine, show a focused edit form.
        if (editMode != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = VirgaSpacing.lg, vertical = VirgaSpacing.sm)
                    .padding(bottom = VirgaSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
            ) {
                Text(
                    stringResource(R.string.remotes_edit_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = VirgaSpacing.sm),
                )
                OutlinedTextField(
                    value = editMode.remoteName,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.remotes_edit_field_name_readonly)) },
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editSchemaOptions != null) {
                    TypedOptionFields(
                        options = editSchemaOptions,
                        values = editTypedValues,
                        showAdvanced = editShowAdvanced,
                        onToggleAdvanced = { editShowAdvanced = !editShowAdvanced },
                        backendType = editMode.remoteType.ifEmpty { null },
                    )
                    // Show hint for password fields — they are blank by default
                    val passwordOpts = editSchemaOptions.filter { it.isPassword }
                    if (passwordOpts.isNotEmpty()) {
                        Text(
                            stringResource(R.string.remotes_edit_password_keep_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        Text(stringResource(R.string.remotes_back))
                    }
                    TextButton(onClick = { onEditConfirm(editTypedValues.toMap()) }) {
                        Text(stringResource(R.string.remotes_edit_save))
                    }
                }
            }
        } else {
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
                    { Text(nameError ?: stringResource(R.string.remotes_add_name_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // The schema load has FINISHED once providersLoaded is non-null (an
            // empty list means it failed or returned nothing). Gate the Picker
            // spinner on this — NOT on pickerEntries — so a failed/empty load
            // falls through to the freeform fallback (else arm) instead of
            // spinning forever (HIGH regression vs v0.1.0).
            val providersLoading = providersLoaded == null
            when {
                // Step: Picker — show loading only while the schema is genuinely
                // still loading.
                step == AddStep.Picker && providersLoading -> {
                    Box(Modifier.fillMaxWidth().padding(VirgaSpacing.xl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                // Step: Picker — show the provider picker when the catalog loaded.
                step == AddStep.Picker && pickerEntries != null -> {
                    ProviderPicker(
                        entries = pickerEntries,
                        setupKindFor = setupKindFor,
                        // UI-M2: gate selection on a usable name so a bundled-OAuth
                        // pick can't fire onOAuth() with a blank name and dead-end
                        // at the post-sign-in state-mismatch check.
                        selectionEnabled = nameUsable,
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
                        TextButton(onClick = onEnsureProviders) {
                            Text(stringResource(R.string.remotes_retry))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { step = AddStep.Picker; type = "" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            Text(stringResource(R.string.remotes_back))
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
                    CredentialFormSection(
                        name = name,
                        nameUsable = nameUsable,
                        type = type,
                        onTypeChange = { type = it },
                        isOAuthByok = (step as? AddStep.CredentialForm)?.isOAuthByok == true,
                        isCrypt = isCrypt,
                        usePicker = pickerEntries != null,
                        schemaOptions = schemaOptions,
                        typedValues = typedValues,
                        showAdvanced = showAdvanced,
                        onToggleAdvanced = { showAdvanced = !showAdvanced },
                        params = params,
                        onParamsChange = { params = it },
                        oauthProviders = oauthProviders,
                        customClientIds = customClientIds,
                        existingRemotes = existingRemotes,
                        error = error,
                        oauthInProgress = oauthInProgress,
                        daemonOAuthTokenPrompt = daemonOAuthTokenPrompt,
                        daemonOAuthFieldPrompt = daemonOAuthFieldPrompt,
                        cryptBaseRemote = cryptBaseRemote,
                        onCryptBaseRemoteSelected = { cryptBaseRemote = it },
                        cryptBasePath = cryptBasePath,
                        onCryptBasePathChange = { cryptBasePath = it },
                        cryptPassword = cryptPassword,
                        onCryptPasswordChange = { cryptPassword = it },
                        cryptSalt = cryptSalt,
                        onCryptSaltChange = { cryptSalt = it },
                        onEnsureProviders = onEnsureProviders,
                        onBack = { step = AddStep.Picker; type = "" },
                        onDismiss = onDismiss,
                        onOAuth = onOAuth,
                        onManualConfirm = onManualConfirm,
                        onCryptConfirm = onCryptConfirm,
                        onDaemonOAuth = onDaemonOAuth,
                        onSubmitDaemonOAuthToken = onSubmitDaemonOAuthToken,
                        onSubmitDaemonOAuthFieldAnswer = onSubmitDaemonOAuthFieldAnswer,
                        onCancelDaemonOAuth = onCancelDaemonOAuth,
                        onSaveClientId = onSaveClientId,
                        onClearClientId = onClearClientId,
                        onSaveClientSecret = onSaveClientSecret,
                        onClearClientSecret = onClearClientSecret,
                    )
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
