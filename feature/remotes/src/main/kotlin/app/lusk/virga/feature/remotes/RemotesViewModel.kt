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
     * Creates a remote from a manual config. [paramsText] is "key=value" per line.
     * Reports the outcome via [onResult] so the dialog can show the error inline —
     * a screen-level snackbar would be hidden behind the still-open Add dialog.
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

    /** Moves rclone.conf in/out via the storage picker — see [ConfigTransferFlow]. */
    private val configTransfer = ConfigTransferFlow(
        scope = viewModelScope,
        context = context,
        repository = repository,
        dispatchers = dispatchers,
        transient = transient,
    )

    /** Imports an existing rclone.conf selected via the storage picker. */
    fun importConfigFromUri(uri: Uri) = configTransfer.importFromUri(uri)

    /** Exports the decrypted rclone.conf to a document created via the storage picker. */
    fun exportConfigToUri(uri: Uri) = configTransfer.exportToUri(uri)

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
            val outcome = try {
                val result = withTimeoutOrNull(CONNECTIVITY_TIMEOUT_MS) {
                    repository.testConnectivity(remoteName)
                }
                if (result?.isSuccess == true) ConnectivityResult.SUCCESS
                else ConnectivityResult.FAILURE
            } catch (e: CancellationException) {
                // Scope cancelled (VM cleared) — the VM is going away, so don't record
                // an outcome; just propagate.
                throw e
            } catch (e: Exception) {
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
                connectivityResults = connectivity.results,
                connectivityTesting = connectivity.testing,
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
            override fun enterNameFirst(): String =
                context.getString(R.string.remotes_msg_enter_name_first)
        },
        onLaunchUrl = { _launchUrl.value = it },
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
