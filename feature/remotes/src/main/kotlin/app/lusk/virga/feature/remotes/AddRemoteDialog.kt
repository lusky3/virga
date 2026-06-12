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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
@OptIn(ExperimentalMaterial3Api::class)
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
    onDismiss: () -> Unit,
    onManualConfirm: (name: String, type: String, params: String) -> Unit,
    onCryptConfirm: (name: String, baseRemote: String, basePath: String, password: String, salt: String) -> Unit = { _, _, _, _, _ -> },
    onWrapperConfirm: (name: String, type: String, params: String) -> Unit = { _, _, _ -> },
    onOAuth: (provider: OAuthProvider, name: String) -> Unit,
    onDaemonOAuth: (type: String, name: String, clientId: String?, clientSecret: String?) -> Unit = { _, _, _, _ -> },
    onSubmitDaemonOAuthToken: (String) -> Unit = {},
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
    val nameError = when {
        name.isBlank() -> null
        name.length > 64 -> "Name must be 64 characters or fewer"
        name.any { it in '\u0000'..'\u001F' } -> "Name can't contain control characters"
        name.trim().any { it == ':' || it == '/' } -> null // handled by existing string
        else -> null
    }
    val nameValid = name.isBlank() || (name.length <= 64 && name.none { it in '\u0000'..'\u001F' } && name.trim().none { it == ':' || it == '/' })
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

            when {
                // Step: Picker — show loading when provider schema hasn't loaded yet
                step == AddStep.Picker && pickerEntries == null -> {
                    Box(Modifier.fillMaxWidth().padding(VirgaSpacing.xl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

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
                        onCancelDaemonOAuth = onCancelDaemonOAuth,
                        onSaveClientId = onSaveClientId,
                        onClearClientId = onClearClientId,
                    )
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
