package app.lusk.virga.feature.remotes

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.toUserMessage
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteOption
import app.lusk.virga.core.common.model.RemoteProvider
import app.lusk.virga.core.common.model.RemoteQuota
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.datastore.OAuthKeyStore
import app.lusk.virga.core.rclone.oauth.OAuthConfig
import app.lusk.virga.core.rclone.oauth.OAuthProvider
import app.lusk.virga.core.rclone.oauth.OAuthProviders
import app.lusk.virga.core.rclone.oauth.OAuthResult
import app.lusk.virga.core.rclone.oauth.OAuthStore
import app.lusk.virga.core.rclone.oauth.OAuthTokenExchanger
import app.lusk.virga.core.rclone.oauth.Pkce
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class RemotesUiState(
    val remotes: List<Remote> = emptyList(),
    val refreshing: Boolean = false,
    val oauthInProgress: Boolean = false,
    val message: String? = null,
    /** providerId → user-supplied client ID, for providers using their own keys. */
    val customClientIds: Map<String, String> = emptyMap(),
    /** remoteName → quota; absent means not yet fetched or unsupported. */
    val quotas: Map<String, RemoteQuota> = emptyMap(),
)

@HiltViewModel
class RemotesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RemoteRepository,
    private val oauthConfig: OAuthConfig,
    private val oauthStore: OAuthStore,
    private val tokenExchanger: OAuthTokenExchanger,
    private val oauthKeyStore: OAuthKeyStore,
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
     * Lazily fetches the `config/providers` schema. Safe to call repeatedly —
     * subsequent calls are no-ops once the value is set. Failures leave the
     * value as an empty list so the UI can detect them and fall back gracefully.
     */
    fun ensureProvidersLoaded() {
        if (_providers.value != null) return
        viewModelScope.launch {
            _providers.value = repository.providers()
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

    init {
        // Observe the redirect activity's results for the lifetime of the VM.
        viewModelScope.launch {
            oauthStore.results.collect { result ->
                if (result != null) {
                    onOAuthResult(result)
                    oauthStore.clearResult()
                }
            }
        }
    }

    private val transient = MutableStateFlow(TransientState())
    private val _quotas = MutableStateFlow<Map<String, RemoteQuota>>(emptyMap())

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
        // once-per-lifetime guard so offline remotes get another chance).
        quotaAttempted.clear()
        _quotas.value = emptyMap()
        viewModelScope.launch {
            transient.value = transient.value.copy(refreshing = true)
            val result = repository.refresh()
            transient.value = TransientState(
                refreshing = false,
                message = result.exceptionOrNull()?.toUserMessage(),
            )
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
            val result = repository.addRemote(name.trim(), type.trim().lowercase(), params)
            if (result.isSuccess) pendingRemoteResult.created(name.trim())
            onResult(result.isSuccess, result.exceptionOrNull()?.toUserMessage())
        }
    }

    private sealed interface ImportRead {
        data object CannotOpen : ImportRead
        data object TooLarge : ImportRead
        data class Ok(val text: String) : ImportRead
    }

    /** Imports an existing rclone.conf selected via the storage picker. */
    fun importConfigFromUri(uri: Uri) {
        viewModelScope.launch {
            val read = withContext(dispatchers.io) {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext ImportRead.CannotOpen
                stream.use {
                    val bytes = it.readBytes()
                    if (bytes.size > MAX_IMPORT_BYTES) ImportRead.TooLarge
                    else ImportRead.Ok(bytes.toString(Charsets.UTF_8))
                }
            }
            when (read) {
                ImportRead.CannotOpen -> transient.value = transient.value.copy(
                    message = context.getString(R.string.remotes_msg_import_failed),
                )
                ImportRead.TooLarge -> transient.value = transient.value.copy(
                    message = context.getString(R.string.remotes_msg_import_too_large),
                )
                is ImportRead.Ok -> {
                    val result = repository.importConfig(read.text)
                    transient.value = transient.value.copy(
                        message = if (result.isSuccess) {
                            context.getString(R.string.remotes_msg_config_imported)
                        } else {
                            result.exceptionOrNull()?.toUserMessage()
                        },
                    )
                }
            }
        }
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 256 * 1024
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
        viewModelScope.launch {
            val result = repository.about(remoteName)
            result.getOrNull()?.let { quota ->
                _quotas.update { it + (remoteName to quota) }
            }
        }
    }

    private data class TransientState(
        val refreshing: Boolean = false,
        val oauthInProgress: Boolean = false,
        val message: String? = null,
    )

    private fun combineState(): StateFlow<RemotesUiState> =
        combine(repository.remotes, transient, oauthKeyStore.clientIds, _quotas) { remotes, t, customIds, quotas ->
            RemotesUiState(
                remotes = remotes,
                refreshing = t.refreshing,
                oauthInProgress = t.oauthInProgress,
                message = t.message,
                customClientIds = customIds,
                quotas = quotas,
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

    /** Starts the OAuth flow for [provider]; observe [launchUrl] to launch Custom Tabs. */
    fun startOAuth(provider: OAuthProvider, remoteName: String) {
        viewModelScope.launch {
            // Prefer the user's own client ID over the shared built-in one.
            val override = oauthKeyStore.clientId(provider.id)
            val clientId = override ?: oauthConfig.clientId(provider.id)
            if (clientId.isBlank()) {
                transient.value = transient.value.copy(
                    message = context.getString(R.string.remotes_msg_oauth_not_configured, provider.displayName),
                )
                return@launch
            }
            // Google derives its redirect from the client ID, so a BYO Google client
            // needs its redirect recomputed from the user's ID (not the built-in).
            val redirectUri = if (provider.id == OAuthProviders.GoogleDrive.id && override != null) {
                OAuthConfig.googleAndroidRedirect(clientId, oauthConfig.redirectUri(provider.id))
            } else {
                oauthConfig.redirectUri(provider.id)
            }
            val pending = OAuthTokenExchanger.PendingAuth(
                provider = provider,
                state = UUID.randomUUID().toString(),
                verifier = Pkce.newVerifier(),
                clientId = clientId,
                redirectUri = redirectUri,
                remoteName = remoteName,
            )
            oauthStore.startPending(pending)
            transient.value = transient.value.copy(oauthInProgress = true, message = null)
            _launchUrl.value = tokenExchanger.authorizeUrl(pending)
        }
    }

    /** Called by the screen once it has handed [launchUrl] to Custom Tabs. */
    fun onLaunchUrlConsumed() {
        _launchUrl.value = null
    }

    private suspend fun onOAuthResult(result: OAuthResult) {
        when (result) {
            is OAuthResult.Error -> {
                // Only tear down the in-flight auth for an error whose state matches
                // the pending one. consume() validates + clears atomically, so an
                // error redirect injected by another app (missing or non-matching
                // state) is a no-op against an unrelated pending flow.
                val state = result.state
                if (state != null && oauthStore.consume(state) != null) {
                    transient.value = transient.value.copy(
                        oauthInProgress = false,
                        message = context.getString(R.string.remotes_msg_sign_in_failed, result.message),
                    )
                }
            }
            is OAuthResult.Success -> {
                val pending = oauthStore.consume(result.state)
                if (pending == null || pending.remoteName.isBlank()) {
                    transient.value = transient.value.copy(
                        oauthInProgress = false,
                        message = context.getString(R.string.remotes_msg_state_mismatch),
                    )
                    return
                }
                val remoteName = pending.remoteName
                val tokenResult = tokenExchanger.exchange(pending, result.code)
                val tokenJson = tokenResult.getOrElse { error ->
                    transient.value = transient.value.copy(
                        oauthInProgress = false,
                        message = error.toUserMessage(),
                    )
                    return
                }
                // Some backends (OneDrive) need extra config derived from the
                // token (drive_id/drive_type) before the remote can list.
                val extras = tokenExchanger.providerConfigExtras(pending.provider, tokenJson).getOrElse { error ->
                    transient.value = transient.value.copy(
                        oauthInProgress = false,
                        message = error.toUserMessage(),
                    )
                    return
                }
                val createResult = repository.addRemote(
                    name = remoteName,
                    type = pending.provider.type,
                    params = mapOf(
                        "token" to tokenJson,
                        "client_id" to pending.clientId,
                    ) + extras,
                )
                if (createResult.isSuccess) pendingRemoteResult.created(remoteName)
                transient.value = transient.value.copy(
                    oauthInProgress = false,
                    message = if (createResult.isSuccess) {
                        context.getString(
                            R.string.remotes_msg_added_remote,
                            pending.provider.displayName,
                            remoteName,
                        )
                    } else {
                        createResult.exceptionOrNull()?.toUserMessage()
                            ?: context.getString(R.string.remotes_msg_could_not_save)
                    },
                )
            }
        }
    }
}
