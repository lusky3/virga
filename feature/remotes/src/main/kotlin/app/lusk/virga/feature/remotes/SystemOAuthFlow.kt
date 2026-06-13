package app.lusk.virga.feature.remotes

import android.content.Context
import app.lusk.virga.core.common.error.toUserMessage
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Drives the bundled (Custom Tabs + PKCE) OAuth flow on behalf of
 * [RemotesViewModel]: builds the authorize URL, registers the pending auth,
 * and completes redirect results by exchanging the code and creating the
 * remote. Extracted as a collaborator so the VM stays focused on screen
 * state; [RemotesViewModel.startOAuth] and the redirect-result collector
 * delegate here unchanged.
 *
 * [transient] is the VM's transient UI state, shared (not copied) so
 * `oauthInProgress` / `message` updates land in the same flow the UI observes.
 */
internal class SystemOAuthFlow(
    private val context: Context,
    private val repository: RemoteRepository,
    private val oauthConfig: OAuthConfig,
    private val oauthStore: OAuthStore,
    private val tokenExchanger: OAuthTokenExchanger,
    private val oauthKeyStore: OAuthKeyStore,
    private val pendingRemoteResult: PendingRemoteResult,
    private val transient: MutableStateFlow<RemotesTransientState>,
    private val onLaunchUrl: (String) -> Unit,
) {

    /** Starts the OAuth flow for [provider]; the authorize URL surfaces via [onLaunchUrl]. */
    suspend fun start(provider: OAuthProvider, remoteName: String) {
        // UI-M2 belt-and-suspenders: a blank remote name would make this round-trip
        // dead-end at the post-sign-in `pending.remoteName.isBlank()` check with a
        // misleading "state mismatch" message. Bail before launching the browser.
        if (remoteName.isBlank()) {
            transient.value = transient.value.copy(
                message = context.getString(R.string.remotes_msg_enter_name_first),
            )
            return
        }
        // Prefer the user's own client ID over the shared built-in one.
        val override = oauthKeyStore.clientId(provider.id)
        val clientId = override ?: oauthConfig.clientId(provider.id)
        if (clientId.isBlank()) {
            transient.value = transient.value.copy(
                message = context.getString(R.string.remotes_msg_oauth_not_configured, provider.displayName),
            )
            return
        }
        // Google derives its redirect from the client ID, so a BYO Google client
        // needs its redirect recomputed from the user's ID (not the built-in).
        val redirectUri = if (provider.id == OAuthProviders.GoogleDrive.id && override != null) {
            OAuthConfig.googleAndroidRedirect(clientId, oauthConfig.redirectUri(provider.id))
        } else {
            oauthConfig.redirectUri(provider.id)
        }
        // UI-M1: a BYO Google client derives a reverse-domain redirect scheme from
        // the user's client ID, but the manifest intent-filter only advertises the
        // scheme baked from the BUILT-IN client ID — so the OS routes the redirect
        // to no activity and the flow dead-ends after sign-in. The manifest scheme
        // cannot be registered at runtime, so refuse to launch when the BYO Google
        // redirect differs from the built-in one.
        if (provider.id == OAuthProviders.GoogleDrive.id && override != null &&
            redirectUri != oauthConfig.redirectUri(provider.id)
        ) {
            transient.value = transient.value.copy(
                message = context.getString(R.string.remotes_msg_byo_google_unsupported),
            )
            return
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
        onLaunchUrl(tokenExchanger.authorizeUrl(pending))
    }

    /**
     * Maps a provider-supplied OAuth2 `error` code (RFC 6749 §4.1.2.1) to a friendly,
     * localized message. Unknown values are surfaced whitespace-collapsed and length-
     * capped rather than echoing arbitrary provider text verbatim into the UI.
     */
    private fun friendlyOAuthError(raw: String): String = when (raw.trim().lowercase()) {
        "access_denied" -> context.getString(R.string.remotes_oauth_err_access_denied)
        "invalid_scope" -> context.getString(R.string.remotes_oauth_err_invalid_scope)
        "server_error" -> context.getString(R.string.remotes_oauth_err_server_error)
        "temporarily_unavailable" -> context.getString(R.string.remotes_oauth_err_temporarily_unavailable)
        else -> raw.replace(Regex("\\s+"), " ").trim().take(120)
    }

    suspend fun onResult(result: OAuthResult) {
        when (result) {
            is OAuthResult.Error -> {
                // Only tear down the in-flight auth for an error whose state matches
                // the pending one. consume() validates + clears atomically, so an
                // error redirect injected by another app (missing or non-matching
                // state) is a no-op against an unrelated pending flow.
                val state = result.state
                if (state != null && oauthStore.consume(state) != null) {
                    transient.update {
                        it.copy(
                            oauthInProgress = false,
                            message = context.getString(
                                R.string.remotes_msg_sign_in_failed,
                                friendlyOAuthError(result.message),
                            ),
                        )
                    }
                } else {
                    // Unmatched / state-less error (e.g. a fabricated redirect injected
                    // by another app). Don't disturb the pending auth — the genuine
                    // redirect still completes via the Success path — but clear the
                    // spinner so a stuck oauthInProgress can't trap the UI forever.
                    transient.update { it.copy(oauthInProgress = false) }
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
                val connectivityWarning = if (createResult.isSuccess) {
                    val connResult = repository.testConnectivity(remoteName)
                    connResult.isFailure
                } else false
                transient.value = transient.value.copy(
                    oauthInProgress = false,
                    message = if (createResult.isSuccess) {
                        if (connectivityWarning) {
                            context.getString(R.string.remotes_msg_connectivity_warning, remoteName)
                        } else {
                            context.getString(
                                R.string.remotes_msg_added_remote,
                                pending.provider.displayName,
                                remoteName,
                            )
                        }
                    } else {
                        createResult.exceptionOrNull()?.toUserMessage()
                            ?: context.getString(R.string.remotes_msg_could_not_save)
                    },
                )
            }
        }
    }
}
