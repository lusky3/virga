package app.lusk.virga.feature.remotes

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.toUserMessage
import app.lusk.virga.core.common.model.RemoteOption
import app.lusk.virga.core.common.model.RemoteProvider
import app.lusk.virga.core.common.model.RemoteQuota
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.datastore.OAuthKeyStore
import app.lusk.virga.core.rclone.PickerEntry
import app.lusk.virga.core.rclone.ProviderCatalog
import app.lusk.virga.core.rclone.SetupKind
import app.lusk.virga.core.rclone.api.RcApiClient
import app.lusk.virga.core.rclone.oauth.DaemonOAuthOrchestrator
import app.lusk.virga.core.rclone.oauth.OAuthConfig
import app.lusk.virga.core.rclone.oauth.OAuthProvider
import app.lusk.virga.core.rclone.oauth.OAuthProviders
import app.lusk.virga.core.rclone.oauth.OAuthStore
import app.lusk.virga.core.rclone.oauth.OAuthTokenExchanger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Returns the set of option names where [RemoteOption.isPassword] is true AND the
 * option is present with a non-blank value in [values].
 */
internal fun sensitiveKeysFrom(
    options: List<RemoteOption>,
    values: Map<String, String>,
): Set<String> = options
    .filter { it.isPassword && values[it.name]?.isNotBlank() == true }
    .map { it.name }
    .toSet()

@HiltViewModel
class RemotesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RemoteRepository,
    private val oauthConfig: OAuthConfig,
    private val oauthStore: OAuthStore,
    private val tokenExchanger: OAuthTokenExchanger,
    private val oauthKeyStore: OAuthKeyStore,
    private val apiClient: RcApiClient,
    private val dispatchers: DispatcherProvider,
    private val pendingRemoteResult: PendingRemoteResult,
) : ViewModel() {

    /** Single-shot signal to the screen: open this URL in Custom Tabs. */
    private val _launchUrl = MutableStateFlow<String?>(null)
    val launchUrl: StateFlow<String?> = _launchUrl

    /** The OAuth providers Virga supports out of the box. */
    val oauthProviders: List<OAuthProvider> = OAuthProviders.All

    /**
     * Provider schema loaded lazily once on first access. Null means not yet
     * loaded; an empty list means the load failed or returned nothing. The UI
     * uses this to decide whether to render typed fields or fall back to the
     * freeform textarea.
     */
    private val _providers = MutableStateFlow<List<RemoteProvider>?>(null)

    /** Public read-only view of the provider schema for the UI to collect. */
    val providers: StateFlow<List<RemoteProvider>?> = _providers

    /**
     * True once a provider-schema load has *finished* — regardless of whether it
     * yielded a usable catalog. Distinguishes "still loading" (false) from "load
     * done but empty/failed" (true with [pickerEntries] still null), so the Add
     * dialog can show a spinner only while genuinely loading and otherwise fall
     * back to the freeform form instead of spinning forever (HIGH regression
     * vs v0.1.0: a failed/empty schema dead-ended on an indefinite spinner).
     */
    val providersReady: StateFlow<Boolean> =
        _providers
            .map { it != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Lazily fetches the `config/providers` schema. Safe to call repeatedly —
     * subsequent calls are no-ops once the value is set. Failures leave the
     * value as an empty list so the UI can detect them and fall back gracefully.
     */
    // Guards against two concurrent ensureProvidersLoaded() calls each launching a
    // providers() fetch (and starting/reusing the daemon twice). Main-confined.
    private var providersLoading = false

    fun ensureProvidersLoaded() {
        if (_providers.value != null || providersLoading) return
        providersLoading = true
        viewModelScope.launch {
            try {
                val loaded = repository.providers()
                _providers.value = loaded
                _catalog = if (loaded.isNullOrEmpty()) null else ProviderCatalog(loaded)
            } finally {
                providersLoading = false
            }
        }
    }

    /**
     * Returns the non-advanced [RemoteOption] list for [backendType], or null when the
     * provider schema has not been loaded or the type is not found. A null return
     * signals the UI to keep the freeform textarea visible.
     */
    fun optionsForBackend(backendType: String): List<RemoteOption>? {
        val loaded = _providers.value ?: return null
        if (loaded.isEmpty()) return null
        return loaded.firstOrNull { it.name.equals(backendType, ignoreCase = true) }
            ?.options
            ?.filter { !it.advanced }
    }

    /**
     * Returns the FULL [RemoteOption] list (basic + advanced) for [backendType], or
     * null when the schema is not loaded or the type is unknown. Use this for the
     * credential form — [TypedOptionFields] partitions basic/advanced itself and
     * needs the advanced options to render the "Show advanced options" expander.
     */
    fun allOptionsForBackend(backendType: String): List<RemoteOption>? {
        val loaded = _providers.value ?: return null
        if (loaded.isEmpty()) return null
        return loaded.firstOrNull { it.name.equals(backendType, ignoreCase = true) }?.options
    }

    /** The [ProviderCatalog] built from the loaded schema, or null before load. */
    private var _catalog: ProviderCatalog? = null
    private val catalog: ProviderCatalog? get() = _catalog

    /** Picker entries for the Add-remote picker, or null when schema is not loaded. */
    fun pickerEntries(): List<PickerEntry>? = catalog?.pickerEntries()

    /** Classification of a backend type for routing the Add-remote flow. */
    fun setupKindFor(type: String): SetupKind = catalog?.setupKind(type) ?: SetupKind.Credential

    private val transient = MutableStateFlow(RemotesTransientState())

    /** Quota fetch results, in-flight set, and the refresh epoch, kept in one flow
     *  so [combineState] stays within the vararg [combine] overload. */
    private data class QuotaState(
        val quotas: Map<String, RemoteQuota> = emptyMap(),
        val loading: Set<String> = emptySet(),
        val epoch: Int = 0,
    )
    private val _quotaState = MutableStateFlow(QuotaState())

    /** Connectivity test results and in-flight set — updated only on explicit user trigger. */
    private data class ConnectivityState(
        val results: Map<String, ConnectivityResult> = emptyMap(),
        val testing: Set<String> = emptySet(),
    )
    private val _connectivityState = MutableStateFlow(ConnectivityState())

    /** Remotes whose quota fetch has been STARTED (regardless of outcome), so a
     *  failing/offline remote isn't re-fetched every time its card scrolls back
     *  into view, and concurrent in-flight fetches for the same remote are
     *  deduped. Declared before the init{} that calls refresh(). Cleared only by
     *  an explicit refresh(). */
    private val quotaAttempted = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    val uiState: StateFlow<RemotesUiState> = combineState()

    init {
        refresh()
    }

    fun refresh() {
        // Allow quotas to be re-fetched on an explicit refresh (clears the
        // once-per-lifetime guard so offline remotes get another chance) and bump
        // the epoch so each card's LaunchedEffect re-fires fetchQuota.
        quotaAttempted.clear()
        _quotaState.update { QuotaState(epoch = it.epoch + 1) }
        viewModelScope.launch {
            transient.update { it.copy(refreshing = true) }
            val result = repository.refresh()
            // update {} (not a fresh TransientState) so a concurrent startOAuth's
            // oauthInProgress set during repository.refresh()'s suspension isn't
            // clobbered when this resumes.
            transient.update { it.copy(refreshing = false, message = result.exceptionOrNull()?.toUserMessage()) }
        }
    }

    fun deleteRemote(name: String) {
        viewModelScope.launch {
            val result = repository.deleteRemote(name)
            transient.value = transient.value.copy(
                message = result.exceptionOrNull()?.toUserMessage(),
            )
        }
    }

    /**
     * Begins editing [name]: ensures the provider schema is loaded BEFORE opening the
     * dialog, then loads the remote's current params.
     *
     * Schema load is a precondition — not a race: [passwordKeys] is derived from the
     * resolved [RemoteProvider] list so it is never empty because the dialog opened before
     * the schema arrived. The dialog is only opened (editMode set) once both the schema
     * and the remote params are available.
     */
    fun beginEditRemote(name: String) {
        viewModelScope.launch {
            transient.update { it.copy(editLoading = true) }
            val typeResult = runCatching {
                // Await schema: if not yet loaded, fetch it now and wait for the result.
                // We call repository.providers() directly so this coroutine suspends until
                // the schema is available, regardless of whether ensureProvidersLoaded()
                // was called from the UI. If it was already fetched (_providers.value != null)
                // we use the cached value via allOptionsForBackend immediately.
                if (_providers.value == null) {
                    val loaded = repository.providers()
                    _providers.value = loaded
                    _catalog = if (loaded.isEmpty()) null else ProviderCatalog(loaded)
                    providersLoading = false
                }
                val config = repository.getRemoteParams(name).getOrThrow()
                val type = config["type"].orEmpty()
                // allOptionsForBackend now has the schema: returns the full option list.
                val options = allOptionsForBackend(type) ?: emptyList()
                // Refuse to open the editor without a usable field schema — otherwise the
                // dialog shows no editable fields and the password-key set can't be derived.
                if (options.isEmpty()) {
                    error(context.getString(R.string.remotes_edit_schema_unavailable))
                }
                val passwordKeys = options.filter { it.isPassword }.map { it.name }.toSet()
                EditModeState(
                    remoteName = name,
                    remoteType = type,
                    loadedParams = config,
                    passwordKeys = passwordKeys,
                )
            }
            val editMode = typeResult.getOrNull()
            val error = typeResult.exceptionOrNull()?.toUserMessage()
            transient.update { it.copy(editLoading = false, editMode = editMode, message = error) }
        }
    }

    fun dismissEditRemote() {
        transient.update { it.copy(editMode = null) }
    }

    /**
     * Submits an edit: diffs [submittedValues] against the loaded snapshot, sends only
     * changed keys to updateRemote. Password keys that the user left blank are skipped
     * (keep current); password keys with a new value are sent and flagged as sensitive.
     */
    fun submitEdit(
        name: String,
        submittedValues: Map<String, String>,
        onResult: (success: Boolean, error: String?) -> Unit,
    ) {
        val snapshot = transient.value.editMode ?: run {
            onResult(false, "No edit session active.")
            return
        }
        viewModelScope.launch {
            val passwordKeys = snapshot.passwordKeys
            val loaded = snapshot.loadedParams
            // Diff: include a key if the submitted value differs from loaded OR it's a new key.
            // Password fields left blank by the user are skipped entirely (keep current).
            val changed = submittedValues.entries.mapNotNull { (k, v) ->
                when {
                    passwordKeys.contains(k) && v.isBlank() -> null  // keep current
                    passwordKeys.contains(k) -> k to v               // new password value
                    v != loaded[k] -> k to v                         // non-password changed
                    else -> null                                      // unchanged
                }
            }.toMap()
            // Nothing edited → don't acquire the exclusive config lock (which would also
            // fail with a confusing "stop running syncs" error if a sync is active).
            if (changed.isEmpty()) {
                transient.update {
                    it.copy(editMode = null, message = context.getString(R.string.remotes_msg_no_changes))
                }
                onResult(true, null)
            } else {
                val sensitiveAmongChanged = changed.keys.intersect(passwordKeys)
                val result = repository.updateRemote(name, changed, sensitiveAmongChanged)
                if (result.isSuccess) {
                    transient.update {
                        it.copy(
                            editMode = null,
                            message = context.getString(R.string.remotes_msg_remote_updated, name),
                        )
                    }
                }
                onResult(result.isSuccess, result.exceptionOrNull()?.toUserMessage())
            }
        }
    }

    /**
     * Creates a remote from a typed params map. Used by the manual-add path when the
     * provider schema is available — the map is forwarded directly without any
     * newline-serialisation round-trip, so multi-line values (e.g. an SFTP PEM in
     * [key_pem]) are preserved intact.
     *
     * Reports the outcome via [onResult] so the dialog can show the error inline —
     * a screen-level snackbar would be hidden behind the still-open Add dialog.
     */
    fun addRemote(
        name: String,
        type: String,
        params: Map<String, String>,
        onResult: (success: Boolean, error: String?) -> Unit,
    ) {
        viewModelScope.launch {
            // rclone backend types are always lowercase ("drive", "s3", …).
            // Lowercase here so a remote still creates even if the keyboard
            // auto-capitalized the first letter (KeyboardCapitalization.None is
            // only a hint and some IMEs ignore it).
            val trimmedType = type.trim().lowercase()
            val options = allOptionsForBackend(trimmedType)
            val sensitiveKeys = options?.let { sensitiveKeysFrom(it, params) } ?: emptySet()
            val result = repository.addRemote(name.trim(), trimmedType, params, sensitiveKeys)
            if (result.isSuccess) {
                pendingRemoteResult.created(name.trim())
                val connResult = repository.testConnectivity(name.trim())
                val warning = when {
                    connResult.isFailure ->
                        context.getString(R.string.remotes_msg_connectivity_warning, name.trim())
                    // Schema unavailable → sensitive keys couldn't be derived, so
                    // any password values went to disk un-obscured. Warn, don't block.
                    options == null ->
                        context.getString(R.string.remotes_msg_freeform_no_obscure)
                    else -> null
                }
                if (warning != null) transient.update { it.copy(message = warning) }
            }
            onResult(result.isSuccess, result.exceptionOrNull()?.toUserMessage())
        }
    }

    /**
     * Creates a remote from a freeform "key=value per line" string. Used by the
     * wrapper-confirm path (which builds its own param string via [buildWrapperParams])
     * and by the freeform-textarea fallback when no schema is available.
     *
     * Multi-line values are NOT safe through this path — callers that may include
     * structured multi-line content (e.g. PEM keys) must use the [Map] overload.
     */
    fun addRemote(
        name: String,
        type: String,
        paramsText: String,
        onResult: (success: Boolean, error: String?) -> Unit,
    ) {
        val params = paramsText.lines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
        addRemote(name, type, params, onResult)
    }

    /** Moves rclone.conf in/out via the storage picker — see [ConfigTransferFlow]. */
    private val configTransfer = ConfigTransferFlow(
        scope = viewModelScope,
        context = context,
        repository = repository,
        dispatchers = dispatchers,
        transient = transient,
    )

    /** Imports an existing rclone.conf selected via the storage picker (replace mode). */
    fun importConfigFromUri(uri: Uri) = configTransfer.importFromUri(uri)

    /**
     * Imports with an explicit [passphrase] for an encrypted container. The crypto
     * layer zeroes [passphrase] before returning. The merge flag is read from transient
     * state so the user's Replace vs Merge choice is honoured even for encrypted files.
     */
    fun importConfigFromUri(uri: Uri, passphrase: CharArray?) =
        configTransfer.importFromUri(uri, passphrase, transient.value.pendingEncryptedImportMerge)

    /** Imports with merge or replace mode for plain files. */
    fun importConfigFromUri(uri: Uri, mergeMode: Boolean) =
        configTransfer.importFromUri(uri, mergeMode = mergeMode)

    /** Imports with [passphrase] and explicit [mergeMode]. */
    fun importConfigFromUri(uri: Uri, passphrase: CharArray?, mergeMode: Boolean) =
        configTransfer.importFromUri(uri, passphrase, mergeMode)

    /** Clears the passphrase prompt without importing (user dismissed the dialog). */
    fun dismissImportPassphrase() {
        transient.value = transient.value.copy(pendingEncryptedImport = null)
    }

    /** Exports the raw (unencrypted) rclone.conf to a document created via the storage picker. */
    fun exportConfigToUri(uri: Uri) = configTransfer.exportToUri(uri)

    /**
     * Exports with an optional [passphrase]. Non-null → encrypted container;
     * null → existing raw-plaintext path unchanged. [redacted]=true exports with
     * sensitive values masked (ignores passphrase).
     */
    fun exportConfigToUri(uri: Uri, passphrase: CharArray?) =
        configTransfer.exportToUri(uri, passphrase)

    /** Exports the config with [redacted]=true (secrets masked) or raw. */
    fun exportConfigToUri(uri: Uri, redacted: Boolean) =
        configTransfer.exportToUri(uri, redacted = redacted)

    // --- Single-remote export ---

    /** Opens the single-remote export dialog for [remoteName]. */
    fun beginSingleRemoteExport(remoteName: String) {
        transient.update { it.copy(singleExportRemote = remoteName) }
    }

    /** Dismisses the single-remote export dialog without action. */
    fun dismissSingleRemoteExport() {
        transient.update { it.copy(singleExportRemote = null) }
    }

    /** Exports [remoteName]'s config section to [uri], optionally redacted. */
    fun exportRemoteSectionToUri(remoteName: String, uri: Uri, redacted: Boolean = false) {
        configTransfer.exportSectionToUri(remoteName, uri, redacted = redacted)
    }

    /**
     * Creates a `crypt:` remote.
     *
     * [baseRemote] and [basePath] are joined as "[baseRemote]:[basePath]" (or
     * "[baseRemote]:" when [basePath] is blank) to form the `remote` parameter
     * that rclone passes to the crypt backend.
     *
     * Plaintext [password] / [salt] values are forwarded to the repository
     * **without logging** and rclone obscures them inside the daemon before
     * they reach disk. This method imposes no further security guarantees
     * beyond what the engine layer provides.
     */
    fun createCrypt(
        name: String,
        baseRemote: String,
        basePath: String,
        password: String,
        salt: String,
        onResult: (success: Boolean, error: String?) -> Unit,
    ) {
        val trimmedPath = basePath.trim().trimStart('/')
        val baseRemoteSpec = if (trimmedPath.isEmpty()) "$baseRemote:" else "$baseRemote:$trimmedPath"
        val saltOrNull = salt.trim().ifBlank { null }
        viewModelScope.launch {
            val result = repository.addCryptRemote(
                name = name.trim(),
                baseRemoteSpec = baseRemoteSpec,
                password = password,
                salt = saltOrNull,
            )
            if (result.isSuccess) {
                pendingRemoteResult.created(name.trim())
                transient.value = transient.value.copy(
                    message = context.getString(R.string.remotes_msg_crypt_created, name.trim()),
                )
            }
            onResult(result.isSuccess, result.exceptionOrNull()?.toUserMessage())
        }
    }

    companion object {
        /** Cap on a single quota (`operations/about`) fetch. Backends that don't
         *  support `about` usually error fast, but a slow/unreachable remote (or a
         *  starting daemon) could otherwise leave the "checking…" bar spinning
         *  forever — this guarantees it always resolves. */
        private const val QUOTA_TIMEOUT_MS = 12_000L
        /** Cap on a single connectivity test; a null result (timeout) → FAILURE. */
        private const val CONNECTIVITY_TIMEOUT_MS = 12_000L
    }

    /**
     * On-demand connectivity test for [remoteName]. Guards only against a concurrent
     * in-flight test; allows re-testing once a prior test has finished.
     */
    fun testConnectivity(remoteName: String) {
        if (remoteName in _connectivityState.value.testing) return
        _connectivityState.update { it.copy(testing = it.testing + remoteName) }
        viewModelScope.launch {
            // Any non-cancellation throw maps to FAILURE so the `testing` flag is always
            // cleared below — otherwise a thrown repository call would leave the remote
            // stuck "testing" forever and the guard would block every later re-test.
            val outcome = runCatching {
                val result = withTimeoutOrNull(CONNECTIVITY_TIMEOUT_MS) {
                    repository.testConnectivity(remoteName)
                }
                if (result?.isSuccess == true) ConnectivityResult.SUCCESS
                else ConnectivityResult.FAILURE
            }.getOrElse { e ->
                // Scope cancelled (VM cleared) → propagate; the VM is going away and
                // shouldn't record an outcome. Anything else is just a failed test.
                if (e is CancellationException) throw e
                ConnectivityResult.FAILURE
            }
            _connectivityState.update { s ->
                s.copy(
                    results = s.results + (remoteName to outcome),
                    testing = s.testing - remoteName,
                )
            }
        }
    }

    fun clearMessage() {
        transient.value = transient.value.copy(message = null)
    }

    /**
     * Runs rclone dedupe on [remoteName]. Requires user confirmation before calling
     * (the caller presents a confirm dialog). Surfaces success/failure via the
     * screen-level snackbar.
     */
    fun dedupeRemote(remoteName: String) {
        viewModelScope.launch {
            val result = repository.dedupe(remoteName)
            val msg = if (result.isSuccess) {
                context.getString(R.string.remotes_dedupe_success, remoteName)
            } else {
                // Surface the rclone reason (e.g. "directory not found", quota) — the
                // engine threads it through; don't collapse it to a generic failure.
                val reason = result.exceptionOrNull()?.toUserMessage().orEmpty()
                context.getString(R.string.remotes_dedupe_failed, remoteName, reason)
            }
            transient.update { it.copy(message = msg) }
        }
    }

    /**
     * Lazily fetches storage quota for [remoteName]. Called from the card's
     * LaunchedEffect. Attempted at most once per VM lifetime per remote (guarded
     * by [quotaAttempted], which records the attempt BEFORE the call so failures
     * and in-flight fetches don't retry). Failures are swallowed — no quota shown.
     */
    fun fetchQuota(remoteName: String) {
        if (!quotaAttempted.add(remoteName)) return
        _quotaState.update { it.copy(loading = it.loading + remoteName) }
        viewModelScope.launch {
            // Time-box the fetch so an unsupported/slow backend can't spin forever;
            // a null result (timeout or failure) simply leaves no quota shown.
            val result = withTimeoutOrNull(QUOTA_TIMEOUT_MS) { repository.about(remoteName) }
            _quotaState.update { s ->
                val quota = result?.getOrNull()
                s.copy(
                    quotas = if (quota != null) s.quotas + (remoteName to quota) else s.quotas,
                    loading = s.loading - remoteName,
                )
            }
        }
    }

    // --- Re-auth / sign-out -------------------------------------------------------

    /**
     * Re-authenticates [remoteName] by running the full OAuth flow for its type. On
     * success the flow calls `addRemote` (rclone's `config/create` is idempotent —
     * it updates the stored token for an existing remote) and then clears the
     * [Remote.needsReauth] flag via [SystemOAuthFlow.onReauthComplete]. On failure
     * the flag is left set so the badge stays visible.
     *
     * Delegates to [SystemOAuthFlow] with [isReauth]=true; `reauthInProgress` is
     * cleared by [clearReauthInProgress] once the redirect result arrives (success
     * or failure).
     *
     * Only OAuth-type remotes reach this path — the badge is only shown when
     * needsReauth is set by the sync worker on an auth-shaped failure. Non-OAuth
     * remotes surface a generic error and abort.
     */
    fun reauthRemote(remoteName: String) {
        // Re-entry guard: a second tap before the redirect arrives would call
        // systemOAuth.start() again, opening another browser tab and orphaning the
        // first pending OAuth state. Mirrors submitRename's renameInFlight guard.
        if (remoteName in transient.value.reauthInProgress) return
        val remote = uiState.value.remotes.firstOrNull { it.name == remoteName } ?: return
        val provider = OAuthProviders.All.firstOrNull {
            it.type.equals(remote.type, ignoreCase = true)
        }
        if (provider == null) {
            transient.update {
                it.copy(message = context.getString(R.string.remotes_msg_reauth_failed, remoteName))
            }
            return
        }
        transient.update { it.copy(reauthInProgress = it.reauthInProgress + remoteName) }
        viewModelScope.launch { systemOAuth.start(provider, remoteName, isReauth = true) }
    }

    /**
     * Signs out of [remoteName] by clearing the stored token via [updateRemote] and
     * setting [Remote.needsReauth] = true so the user is prompted to re-authenticate
     * before the next sync. Rclone accepts an empty token via config/update — it stores
     * the blank and will fail on next use (requiring re-auth, which is exactly the intent).
     */
    fun signOutRemote(remoteName: String) {
        viewModelScope.launch {
            val updateResult = repository.updateRemote(remoteName, mapOf("token" to ""))
            val setResult = if (updateResult.isSuccess) {
                runCatching { repository.setNeedsReauth(remoteName, true) }
            } else {
                val cause = updateResult.exceptionOrNull()
                    ?: RuntimeException("Sign-out failed for $remoteName")
                Result.failure(cause)
            }
            val msg = if (setResult.isSuccess) {
                context.getString(R.string.remotes_msg_sign_out_done, remoteName)
            } else {
                setResult.exceptionOrNull()?.toUserMessage()
            }
            transient.update { it.copy(message = msg) }
        }
    }

    /** Called after a re-auth OAuth flow completes (success or failure) to clear the in-progress flag. */
    internal fun clearReauthInProgress(remoteName: String) {
        transient.update { it.copy(reauthInProgress = it.reauthInProgress - remoteName) }
    }

    // --- Rename remote -----------------------------------------------------------

    /** Opens the rename dialog for [name]. Mutually exclusive with edit/add. */
    fun beginRenameRemote(name: String) {
        transient.update { it.copy(renameTarget = name, editMode = null) }
    }

    /** Dismisses the rename dialog without any action. */
    fun dismissRenameRemote() {
        transient.update { it.copy(renameTarget = null, renameInFlight = false) }
    }

    /**
     * Validates [newName] and, if valid, renames the remote from [oldName] to
     * [newName]. Returns an error string on validation failure (inline in dialog)
     * or null on success. Surfaces result via snackbar and refreshes the list.
     *
     * Validation rules (mirror AddRemoteDialog):
     * - [newName] must be non-blank.
     * - [newName] must differ from [oldName].
     * - [newName] must not already be used by another remote.
     * - [newName] must be structurally valid (isValidRemoteName).
     */
    fun submitRename(
        oldName: String,
        newName: String,
        onValidationError: (String) -> Unit,
    ) {
        // Guard against a double-tap firing two non-idempotent renameRemote calls
        // (the second would fail after the first already deleted the old remote).
        if (transient.value.renameInFlight) return
        val trimmed = newName.trim()
        val existingNames = uiState.value.remotes.map { it.name }
        val validationError = when {
            trimmed.isBlank() ->
                context.getString(R.string.remotes_rename_error_blank)
            trimmed == oldName ->
                context.getString(R.string.remotes_rename_error_same)
            existingNames.any { it == trimmed } ->
                context.getString(R.string.remotes_rename_error_taken, trimmed)
            !app.lusk.virga.core.common.validation.isValidRemoteName(trimmed) ->
                context.getString(R.string.remotes_add_name_invalid)
            else -> null
        }
        if (validationError != null) {
            onValidationError(validationError)
            return
        }
        transient.update { it.copy(renameInFlight = true) }
        viewModelScope.launch {
            val result = repository.renameRemote(oldName, trimmed)
            transient.update { state ->
                val msg = if (result.isSuccess) {
                    context.getString(R.string.remotes_msg_remote_renamed, oldName, trimmed)
                } else {
                    result.exceptionOrNull()?.toUserMessage()
                }
                state.copy(renameTarget = null, renameInFlight = false, message = msg)
            }
        }
    }

    private fun combineState(): StateFlow<RemotesUiState> =
        combine(
            repository.remotes,
            transient,
            oauthKeyStore.clientIds,
            _quotaState,
            _connectivityState,
        ) { remotes, t, customIds, quota, connectivity ->
            RemotesUiState(
                remotes = remotes,
                refreshing = t.refreshing,
                oauthInProgress = t.oauthInProgress,
                message = t.message,
                customClientIds = customIds,
                quotas = quota.quotas,
                quotaLoading = quota.loading,
                quotaEpoch = quota.epoch,
                daemonOAuthTokenPrompt = t.daemonOAuthTokenPrompt,
                daemonOAuthFieldPrompt = t.daemonOAuthFieldPrompt,
                connectivityResults = connectivity.results,
                connectivityTesting = connectivity.testing,
                pendingEncryptedImport = t.pendingEncryptedImport,
                pendingEncryptedImportMerge = t.pendingEncryptedImportMerge,
                editMode = t.editMode,
                editLoading = t.editLoading,
                renameTarget = t.renameTarget,
                renameInFlight = t.renameInFlight,
                reauthInProgress = t.reauthInProgress,
                singleExportRemote = t.singleExportRemote,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemotesUiState())

    // --- Bring-your-own OAuth client IDs --------------------------------

    /** Saves (or clears, when blank) the user's own client ID for [providerId]. */
    fun saveClientId(providerId: String, clientId: String) {
        viewModelScope.launch { oauthKeyStore.setClientId(providerId, clientId) }
    }

    fun clearClientId(providerId: String) {
        viewModelScope.launch { oauthKeyStore.clearClientId(providerId) }
    }

    /** Saves (or clears, when blank) the user's own client secret for [providerId]. */
    fun saveClientSecret(providerId: String, secret: String) {
        viewModelScope.launch { oauthKeyStore.setClientSecret(providerId, secret) }
    }

    fun clearClientSecret(providerId: String) {
        viewModelScope.launch { oauthKeyStore.clearClientSecret(providerId) }
    }

    // --- OAuth ----------------------------------------------------------

    /** Daemon-mediated OAuth flow for non-bundled providers — see [DaemonOAuthFlow]. */
    private val daemonOAuth = DaemonOAuthFlow(
        scope = viewModelScope,
        repository = repository,
        apiClient = apiClient,
        dispatchers = dispatchers,
        pendingRemoteResult = pendingRemoteResult,
        transient = transient,
        strings = object : DaemonOAuthFlow.Strings {
            override fun connectivityWarning(remoteName: String): String =
                context.getString(R.string.remotes_msg_connectivity_warning, remoteName)
            override fun oauthTimedOut(): String =
                context.getString(R.string.remotes_msg_oauth_timed_out)
            override fun addedRemote(remoteName: String): String =
                context.getString(R.string.remotes_msg_added_remote_named, remoteName)
            override fun reauthSuccess(remoteName: String): String =
                context.getString(R.string.remotes_msg_reauth_success, remoteName)
            override fun enterNameFirst(): String =
                context.getString(R.string.remotes_msg_enter_name_first)
        },
        onLaunchUrl = { _launchUrl.value = it },
        onReauthComplete = { name, _ -> clearReauthInProgress(name) },
    )

    /** Bundled (Custom Tabs + PKCE) OAuth flow — see [SystemOAuthFlow]. */
    private val systemOAuth = SystemOAuthFlow(
        context = context,
        repository = repository,
        oauthConfig = oauthConfig,
        oauthStore = oauthStore,
        tokenExchanger = tokenExchanger,
        oauthKeyStore = oauthKeyStore,
        pendingRemoteResult = pendingRemoteResult,
        transient = transient,
        onLaunchUrl = { _launchUrl.value = it },
        onReauthComplete = { name, _ -> clearReauthInProgress(name) },
        // BYO-Google-with-secret: hand off to the daemon flow so the app redirect isn't needed.
        // isReauth is threaded through so re-auth flows don't delete pre-existing remotes.
        onDaemonOAuth = { type, name, clientId, clientSecret, isReauth ->
            daemonOAuth.start(type, name, clientId, clientSecret, isReauth)
        },
    )

    /** Test seam — see [DaemonOAuthFlow.orchestratorFactory]. */
    internal var daemonOAuthOrchestratorFactory: () -> DaemonOAuthOrchestrator
        get() = daemonOAuth.orchestratorFactory
        set(value) {
            daemonOAuth.orchestratorFactory = value
        }

    /** Starts daemon-mediated OAuth for a non-bundled provider. Observe [launchUrl] for the auth URL. */
    fun startDaemonOAuth(
        type: String,
        name: String,
        clientId: String? = null,
        clientSecret: String? = null,
    ) = daemonOAuth.start(type, name, clientId, clientSecret)

    /**
     * Forwards the token the user pasted (output of `rclone authorize` run on
     * another machine) to the in-flight daemon OAuth flow and dismisses the
     * prompt. No-op when no flow is awaiting a token.
     */
    fun submitDaemonOAuthToken(token: String) = daemonOAuth.submitToken(token)

    /**
     * Forwards a field answer the user supplied to the in-flight daemon OAuth
     * flow and dismisses the field prompt. No-op when no flow is awaiting input.
     */
    fun submitDaemonOAuthFieldAnswer(answer: String) = daemonOAuth.submitFieldAnswer(answer)

    fun cancelDaemonOAuth() = daemonOAuth.cancel()

    /** Starts the OAuth flow for [provider]; observe [launchUrl] to launch Custom Tabs. */
    fun startOAuth(provider: OAuthProvider, remoteName: String) {
        viewModelScope.launch { systemOAuth.start(provider, remoteName) }
    }

    /** Called by the screen once it has handed [launchUrl] to Custom Tabs. */
    fun onLaunchUrlConsumed() {
        _launchUrl.value = null
    }

    // Declared LAST so the collector runs only after systemOAuth (and transient)
    // are initialized. viewModelScope uses Dispatchers.Main.immediate and the VM
    // is built on the main thread, so the launch body executes synchronously inside
    // the constructor, and OAuthStore retains a result across VM teardown — a
    // collector placed earlier would touch the not-yet-initialized systemOAuth and
    // NPE when the user returns from a backgrounded OAuth redirect.
    init {
        // Observe the redirect activity's results for the lifetime of the VM.
        viewModelScope.launch {
            oauthStore.results.collect { result ->
                if (result != null) {
                    systemOAuth.onResult(result)
                    oauthStore.clearResult()
                }
            }
        }
    }
}
