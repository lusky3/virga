package app.lusk.virga.feature.remotes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.error.toUserMessage
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.rclone.oauth.OAuthConfig
import app.lusk.virga.core.rclone.oauth.OAuthProvider
import app.lusk.virga.core.rclone.oauth.OAuthProviders
import app.lusk.virga.core.rclone.oauth.OAuthResult
import app.lusk.virga.core.rclone.oauth.OAuthStore
import app.lusk.virga.core.rclone.oauth.OAuthTokenExchanger
import app.lusk.virga.core.rclone.oauth.Pkce
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class RemotesUiState(
    val remotes: List<RemoteEntity> = emptyList(),
    val refreshing: Boolean = false,
    val oauthInProgress: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class RemotesViewModel @Inject constructor(
    private val repository: RemoteRepository,
    private val oauthConfig: OAuthConfig,
    private val oauthStore: OAuthStore,
    private val tokenExchanger: OAuthTokenExchanger,
) : ViewModel() {

    /** Single-shot signal to the screen: open this URL in Custom Tabs. */
    private val _launchUrl = MutableStateFlow<String?>(null)
    val launchUrl: StateFlow<String?> = _launchUrl

    /** The OAuth providers Virga supports out of the box. */
    val oauthProviders: List<OAuthProvider> = OAuthProviders.All

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

    val uiState: StateFlow<RemotesUiState> = combineState()

    init {
        refresh()
    }

    fun refresh() {
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
            onResult(result.isSuccess, result.exceptionOrNull()?.toUserMessage())
        }
    }

    /** Imports an existing rclone.conf (e.g. exported from desktop). */
    fun importConfig(confText: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = repository.importConfig(confText)
            if (result.isSuccess) {
                val count = confText.lines().count { it.startsWith("[") && it.endsWith("]") }
                transient.value = transient.value.copy(
                    message = "Imported $count remote${if (count == 1) "" else "s"}.",
                )
                onDone()
            } else {
                transient.value = transient.value.copy(
                    message = result.exceptionOrNull()?.toUserMessage(),
                )
            }
        }
    }

    fun clearMessage() {
        transient.value = transient.value.copy(message = null)
    }

    private data class TransientState(
        val refreshing: Boolean = false,
        val oauthInProgress: Boolean = false,
        val message: String? = null,
    )

    private fun combineState(): StateFlow<RemotesUiState> =
        combine(repository.remotes, transient) { remotes, t ->
            RemotesUiState(
                remotes = remotes,
                refreshing = t.refreshing,
                oauthInProgress = t.oauthInProgress,
                message = t.message,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemotesUiState())

    // --- OAuth ----------------------------------------------------------

    /** Starts the OAuth flow for [provider]; observe [launchUrl] to launch Custom Tabs. */
    fun startOAuth(provider: OAuthProvider, remoteName: String) {
        val clientId = oauthConfig.clientId(provider.id)
        if (clientId.isBlank()) {
            transient.value = transient.value.copy(
                message = "${provider.displayName} OAuth client ID isn't configured yet.",
            )
            return
        }
        val pending = OAuthTokenExchanger.PendingAuth(
            provider = provider,
            state = UUID.randomUUID().toString(),
            verifier = Pkce.newVerifier(),
            clientId = clientId,
            redirectUri = oauthConfig.redirectUri(provider.id),
            remoteName = remoteName,
        )
        oauthStore.startPending(pending)
        transient.value = transient.value.copy(oauthInProgress = true, message = null)
        _launchUrl.value = tokenExchanger.authorizeUrl(pending)
    }

    /** Called by the screen once it has handed [launchUrl] to Custom Tabs. */
    fun onLaunchUrlConsumed() {
        _launchUrl.value = null
    }

    private suspend fun onOAuthResult(result: OAuthResult) {
        when (result) {
            is OAuthResult.Error -> {
                oauthStore.clear()
                transient.value = transient.value.copy(
                    oauthInProgress = false,
                    message = "Sign-in failed: ${result.message}",
                )
            }
            is OAuthResult.Success -> {
                val pending = oauthStore.consume(result.state)
                if (pending == null || pending.remoteName.isBlank()) {
                    transient.value = transient.value.copy(
                        oauthInProgress = false,
                        message = "Sign-in state mismatch; please try again.",
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
                val createResult = repository.addRemote(
                    name = remoteName,
                    type = pending.provider.type,
                    params = mapOf(
                        "token" to tokenJson,
                        "client_id" to pending.clientId,
                    ),
                )
                transient.value = transient.value.copy(
                    oauthInProgress = false,
                    message = if (createResult.isSuccess) {
                        "Added ${pending.provider.displayName} remote \"$remoteName\""
                    } else {
                        createResult.exceptionOrNull()?.toUserMessage()
                            ?: "Could not save remote."
                    },
                )
            }
        }
    }
}
