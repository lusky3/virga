package app.lusk.virga.feature.remotes

import android.net.Uri
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteQuota

/**
 * UI-facing descriptor for a daemon-OAuth required field that has no usable
 * default. The VM surfaces this to the UI while the orchestrator awaits the
 * user's answer via [RemotesViewModel.submitDaemonOAuthFieldAnswer].
 */
data class DaemonOAuthFieldPrompt(
    val optionName: String,
    val label: String,
    val help: String,
    val examples: List<String> = emptyList(),
    val isPassword: Boolean = false,
)

/** Per-remote connectivity test outcome — set only after an explicit user trigger. */
enum class ConnectivityResult { SUCCESS, FAILURE }

/**
 * Snapshot of a remote's loaded config, kept in the ViewModel during an edit session.
 * [loadedParams] contains all params as returned by config/get (including obscured secrets).
 * It is never exposed to the UI — the dialog receives only the non-password values for
 * pre-filling, and [passwordKeys] is used to blank those fields.
 */
data class EditModeState(
    val remoteName: String,
    val remoteType: String,
    /** Full params snapshot from config/get, including obscured secrets. Never surfaces to UI. */
    val loadedParams: Map<String, String>,
    /** Keys whose RemoteOption.isPassword == true. These are blanked in the edit dialog. */
    val passwordKeys: Set<String>,
)

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
    /** Non-null while the daemon-OAuth state machine is awaiting a user-supplied
     *  field value for a required option with no usable default. */
    val daemonOAuthFieldPrompt: DaemonOAuthFieldPrompt? = null,
    /** remoteName → connectivity result; absent means not yet tested. */
    val connectivityResults: Map<String, ConnectivityResult> = emptyMap(),
    /** Remotes whose connectivity test is currently in flight. */
    val connectivityTesting: Set<String> = emptySet(),
    /** Non-null while the UI is waiting for the user to supply a passphrase to
     *  decrypt the encrypted config at this [Uri]. Cleared on success or dismiss. */
    val pendingEncryptedImport: Uri? = null,
    /** True when [pendingEncryptedImport] should be imported in merge mode; false = replace. */
    val pendingEncryptedImportMerge: Boolean = false,
    /** Non-null while the user is editing an existing remote. */
    val editMode: EditModeState? = null,
    /** True while [editMode] is being loaded via getRemoteParams. */
    val editLoading: Boolean = false,
    /** Non-null while the user has triggered rename for a remote (holds old name). */
    val renameTarget: String? = null,
    /** True while a rename is in flight (blocks further submits). */
    val renameInFlight: Boolean = false,
    /** Remotes currently running a re-auth OAuth flow. */
    val reauthInProgress: Set<String> = emptySet(),
    /** Name of the remote selected for single-remote export (non-null = dialog open). */
    val singleExportRemote: String? = null,
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
    /** Non-null while the daemon-OAuth state machine is awaiting a user-supplied
     *  field value for a required option with no usable default. */
    val daemonOAuthFieldPrompt: DaemonOAuthFieldPrompt? = null,
    /** Non-null while awaiting a passphrase to decrypt an encrypted import. Cleared
     *  on successful decrypt, dismiss, or when a non-encrypted file is imported. */
    val pendingEncryptedImport: Uri? = null,
    /** True when [pendingEncryptedImport] should be imported in merge mode; false = replace. */
    val pendingEncryptedImportMerge: Boolean = false,
    /** Non-null while the user is editing an existing remote. */
    val editMode: EditModeState? = null,
    /** True while [editMode] is being loaded via getRemoteParams. */
    val editLoading: Boolean = false,
    /** Non-null while the user has triggered rename for a remote (holds old name). */
    val renameTarget: String? = null,
    /** True while a rename is in flight (blocks further submits). */
    val renameInFlight: Boolean = false,
    /** Remotes currently running a re-auth OAuth flow. */
    val reauthInProgress: Set<String> = emptySet(),
    /** Name of the remote selected for single-remote export (non-null = dialog open). */
    val singleExportRemote: String? = null,
)
