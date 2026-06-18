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
    /** Called when a re-auth flow completes. [success] is true on token exchange OK. */
    private val onReauthComplete: (remoteName: String, success: Boolean) -> Unit = { _, _ -> },
    /**
     * Called when a BYO-Google-with-secret flow is detected; hands off to the
     * daemon OAuth path instead of Custom Tabs. See [ByoGoogleDaemonCallback].
     */
    private val onDaemonOAuth: ByoGoogleDaemonCallback = { _, _, _, _, _ -> },
) {

    /**
     * Starts the OAuth flow for [provider]; the authorize URL surfaces via [onLaunchUrl].
     * Set [isReauth] = true when re-authenticating an existing remote so the result
     * handler clears needsReauth on success instead of reporting it as a new addition.
     *
     * When the user has set BOTH a BYO Google client ID and a BYO client secret, the flow
     * is routed through [onDaemonOAuth] instead of the PKCE Custom-Tabs path, because a
     * BYO Google client's reverse-domain redirect can't be registered in the manifest at
     * runtime (UI-M1). With a secret, the rclone daemon flow can complete the authorization
     * without the app redirect scheme. The unsupported-BYO message fires only when the
     * override client ID is present but NO secret is provided (PKCE BYO-Google can't work).
     */
    suspend fun start(provider: OAuthProvider, remoteName: String, isReauth: Boolean = false) {
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
        // BYO-Google-with-secret: route to daemon flow so the app redirect scheme is not needed.
        val byoSecret = if (override != null) oauthKeyStore.clientSecret(provider.id) else null
        if (shouldUseDaemonForByoGoogle(provider.id, override, byoSecret)) {
            onDaemonOAuth(provider.type, remoteName, override, byoSecret, isReauth)
            return
        }
        // Resolve the redirect URI (recomputed for a BYO Google client) and reject the
        // runtime-unregisterable BYO-Google scheme; a null result means the flow was
        // refused and the user-facing message is already set.
        val redirectUri = resolveRedirectUri(provider, clientId, override) ?: return
        val pending = OAuthTokenExchanger.PendingAuth(
            provider = provider,
            state = UUID.randomUUID().toString(),
            verifier = Pkce.newVerifier(),
            clientId = clientId,
            redirectUri = redirectUri,
            remoteName = remoteName,
            isReauth = isReauth,
        )
        oauthStore.startPending(pending)
        transient.value = transient.value.copy(oauthInProgress = true, message = null)
        onLaunchUrl(tokenExchanger.authorizeUrl(pending))
    }

    /**
     * Aborts an in-flight Custom-Tabs OAuth flow when the user dismisses the browser
     * without completing sign-in. No redirect is delivered in that case, so [onResult]
     * never fires and the progress spinner would otherwise hang indefinitely. Drops the
     * pending authorization and clears the in-progress flags, leaving any [Remote.needsReauth]
     * badge intact so a re-auth can be retried.
     */
    fun cancel() {
        oauthStore.clear()
        transient.update {
            it.copy(
                oauthInProgress = false,
                reauthInProgress = emptySet(),
                message = context.getString(R.string.remotes_msg_sign_in_canceled),
            )
        }
    }

    /**
     * Resolves the redirect URI for [provider] given the resolved [clientId] and whether
     * a BYO [override] client is in use. Google derives its redirect from the client ID,
     * so a BYO Google client needs its redirect recomputed from the user's ID.
     *
     * UI-M1: a BYO Google client derives a reverse-domain redirect scheme from the user's
     * client ID, but the manifest intent-filter only advertises the scheme baked from the
     * BUILT-IN client ID — so the OS routes the redirect to no activity and the flow
     * dead-ends after sign-in. The manifest scheme cannot be registered at runtime, so
     * returns null (and sets the user-facing message) when the BYO Google redirect differs
     * from the built-in one. This branch is only reached when NO BYO secret is set (i.e.
     * the daemon-routing guard in [start] has already returned for the with-secret case).
     */
    private fun resolveRedirectUri(provider: OAuthProvider, clientId: String, override: String?): String? {
        val isByoGoogle = provider.id == OAuthProviders.GoogleDrive.id && override != null
        val builtInRedirect = oauthConfig.redirectUri(provider.id)
        val redirectUri = if (isByoGoogle) {
            OAuthConfig.googleAndroidRedirect(clientId, builtInRedirect)
        } else {
            builtInRedirect
        }
        if (isByoGoogle && redirectUri != builtInRedirect) {
            transient.value = transient.value.copy(
                message = context.getString(R.string.remotes_msg_byo_google_unsupported),
            )
            return null
        }
        return redirectUri
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
                val consumed = if (state != null) oauthStore.consume(state) else null
                if (consumed != null) {
                    // Re-auth failure: clear the in-progress flag but leave needsReauth
                    // set so the badge stays visible (the user still needs to re-auth).
                    if (consumed.isReauth) onReauthComplete(consumed.remoteName, false)
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
                    if (pending.isReauth) onReauthComplete(remoteName, false)
                    transient.value = transient.value.copy(
                        oauthInProgress = false,
                        message = error.toUserMessage(),
                    )
                    return
                }
                // Some backends (OneDrive) need extra config derived from the
                // token (drive_id/drive_type) before the remote can list.
                val extras = tokenExchanger.providerConfigExtras(pending.provider, tokenJson).getOrElse { error ->
                    if (pending.isReauth) onReauthComplete(remoteName, false)
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
                if (createResult.isSuccess) {
                    if (pending.isReauth) {
                        // Token refreshed: clear the re-auth flag so the badge goes away.
                        runCatching { repository.setNeedsReauth(remoteName, false) }
                        onReauthComplete(remoteName, true)
                    } else {
                        pendingRemoteResult.created(remoteName)
                    }
                } else {
                    if (pending.isReauth) onReauthComplete(remoteName, false)
                }
                val connectivityWarning = if (createResult.isSuccess) {
                    val connResult = repository.testConnectivity(remoteName)
                    connResult.isFailure
                } else false
                transient.value = transient.value.copy(
                    oauthInProgress = false,
                    message = if (createResult.isSuccess) {
                        if (pending.isReauth) {
                            context.getString(R.string.remotes_msg_reauth_success, remoteName)
                        } else if (connectivityWarning) {
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

/**
 * Callback invoked when a BYO-Google-with-secret flow must be handed off to the
 * rclone daemon path instead of Custom Tabs.
 *
 * Parameters: `(type, name, clientId, clientSecret, isReauth)`.
 * [isReauth] mirrors [SystemOAuthFlow.start]'s own flag so the daemon path can
 * treat the flow as a token-refresh rather than a new-remote creation.
 */
internal typealias ByoGoogleDaemonCallback =
    (type: String, name: String, clientId: String?, clientSecret: String?, isReauth: Boolean) -> Unit

/**
 * True when the OAuth flow for a Google Drive provider should be routed through the
 * rclone daemon instead of the Custom-Tabs PKCE path.
 *
 * Routing condition: the provider is Google Drive, the user has set a BYO client ID
 * ([override] is non-null), AND a BYO client secret ([secret] is non-null/blank).
 * With both credentials present the daemon flow can complete without the app-registered
 * redirect scheme, making BYO-Google viable. Without a secret the PKCE path would be
 * attempted — but its redirect scheme can't be registered at runtime (UI-M1), so the
 * caller keeps the existing unsupported message for that sub-case.
 *
 * Extracted as a pure top-level function so it can be unit-tested without instantiating
 * the full flow.
 */
internal fun shouldUseDaemonForByoGoogle(
    providerId: String,
    override: String?,
    secret: String?,
): Boolean = providerId == OAuthProviders.GoogleDrive.id &&
    !override.isNullOrBlank() &&
    !secret.isNullOrBlank()
