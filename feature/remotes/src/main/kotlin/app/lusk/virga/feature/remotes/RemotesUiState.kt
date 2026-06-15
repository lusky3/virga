package app.lusk.virga.feature.remotes

import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteQuota

/** Per-remote connectivity test outcome — set only after an explicit user trigger. */
enum class ConnectivityResult { SUCCESS, FAILURE }

data class RemotesUiState(
    val remotes: List<Remote> = emptyList(),
    val refreshing: Boolean = false,
    val oauthInProgress: Boolean = false,
    val message: String? = null,
    /** providerId → user-supplied client ID, for providers using their own keys. */
    val customClientIds: Map<String, String> = emptyMap(),
    /** remoteName → quota; absent means not yet fetched or unsupported. */
    val quotas: Map<String, RemoteQuota> = emptyMap(),
    /** Remotes whose quota fetch is currently in flight — show a loading bar. */
    val quotaLoading: Set<String> = emptySet(),
    /** Bumped by [RemotesViewModel.refresh]; cards key their quota fetch on it so
     *  a pull-to-refresh re-fires the lazy fetch even though the card stays composed. */
    val quotaEpoch: Int = 0,
    /** rclone's instructions for the daemon-OAuth paste-token prompt (run
     *  `rclone authorize` on another machine, paste the result back); null
     *  when no token is being awaited. */
    val daemonOAuthTokenPrompt: String? = null,
    /** remoteName → connectivity result; absent means not yet tested. */
    val connectivityResults: Map<String, ConnectivityResult> = emptyMap(),
    /** Remotes whose connectivity test is currently in flight. */
    val connectivityTesting: Set<String> = emptySet(),
)

/**
 * The VM-owned transient slice of [RemotesUiState]. Top-level (not nested in the
 * VM) because the OAuth flow collaborators ([DaemonOAuthFlow], [SystemOAuthFlow])
 * share the same `MutableStateFlow` instance and apply their updates to it.
 */
internal data class RemotesTransientState(
    val refreshing: Boolean = false,
    val oauthInProgress: Boolean = false,
    val message: String? = null,
    /** Paste-token instructions from the daemon OAuth flow; null = no prompt. */
    val daemonOAuthTokenPrompt: String? = null,
)
