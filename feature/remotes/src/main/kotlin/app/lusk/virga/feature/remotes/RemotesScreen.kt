package app.lusk.virga.feature.remotes

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.designsystem.component.EmptyState
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import app.lusk.virga.feature.remotes.oauth.launchCustomTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotesScreen(
    onOpenBrowser: (remoteName: String?) -> Unit,
    onCreateTask: (String) -> Unit = {},
    viewModel: RemotesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launchUrl by viewModel.launchUrl.collectAsStateWithLifecycle()
    val providersLoaded by viewModel.providers.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var remoteToDelete by remember { mutableStateOf<Remote?>(null) }
    var remoteToDedupe by remember { mutableStateOf<Remote?>(null) }
    var manualError by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    // Passphrase stashed between the export-dialog confirm and the launcher callback.
    // Null → raw export; non-null → encrypted. Zeroed in the launcher callback.
    var stagedExportPassphrase by remember { mutableStateOf<CharArray?>(null) }
    var stagedExportRedacted by remember { mutableStateOf(false) }
    var uriToImport by remember { mutableStateOf<Uri?>(null) }
    // Name and redacted flag staged between single-export dialog confirm and launcher callback.
    var stagedSingleExportRemote by remember { mutableStateOf<String?>(null) }
    var stagedSingleExportRedacted by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { !listState.canScrollBackward } }

    LaunchedEffect(launchUrl) {
        launchUrl?.let { url ->
            launchCustomTab(context, url)
            viewModel.onLaunchUrlConsumed()
            showAdd = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) uriToImport = uri
    }

    // Export writes the rclone.conf (plain or encrypted) to a user-chosen document.
    // A null uri means the user cancelled the system create-document picker — no-op.
    // stagedExportPassphrase is consumed here and zeroed by the VM/crypto layer.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            if (stagedExportRedacted) {
                viewModel.exportConfigToUri(uri, redacted = true)
            } else {
                viewModel.exportConfigToUri(uri, stagedExportPassphrase)
            }
        } else {
            stagedExportPassphrase?.fill(' ')
        }
        stagedExportPassphrase = null
        stagedExportRedacted = false
    }

    // Single-remote export launcher: writes one remote's section to a user-chosen document.
    val singleRemoteExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val name = stagedSingleExportRemote
        val redacted = stagedSingleExportRedacted
        if (uri != null && name != null) {
            viewModel.exportRemoteSectionToUri(name, uri, redacted)
        }
        stagedSingleExportRemote = null
        stagedSingleExportRedacted = false
    }

    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            // L5: clear BEFORE showing so a nav-away (which cancels showSnackbar)
            // can't leave the message set and replay it on return.
            viewModel.clearMessage()
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remotes_title)) },
                actions = {
                    TextButton(onClick = { onOpenBrowser(null) }) {
                        Text(stringResource(R.string.remotes_action_browse))
                    }
                    TextButton(onClick = { importLauncher.launch("*/*") }) {
                        Text(stringResource(R.string.remotes_action_import))
                    }
                    TextButton(onClick = { showExportDialog = true }) {
                        Text(stringResource(R.string.remotes_action_export))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { manualError = null; showAdd = true },
                expanded = fabExpanded,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.remotes_fab_label)) },
            )
        },
    ) { padding ->
        val oauthProgressCd = stringResource(R.string.remotes_cd_oauth_progress)
        val loadingCd = stringResource(R.string.remotes_cd_loading)

        if (state.oauthInProgress) {
            // Modal so it can't be hidden behind the remote list, and so the Cancel
            // escape hatch is always reachable: a dismissed Custom Tab delivers no
            // redirect, so without a way out the flow would hang forever (no onResult
            // arrives to clear oauthInProgress). Dismiss == cancel.
            AlertDialog(
                onDismissRequest = { viewModel.cancelOAuth() },
                title = { Text(stringResource(R.string.remotes_oauth_in_progress)) },
                text = {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = oauthProgressCd },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.cancelOAuth() }) {
                        Text(stringResource(R.string.remotes_oauth_cancel))
                    }
                },
            )
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.refreshing && state.remotes.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = loadingCd
                            },
                        )
                    }
                }
                state.remotes.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Filled.CloudOff,
                        title = stringResource(R.string.remotes_empty_title),
                        body = stringResource(R.string.remotes_empty_body),
                        action = {
                            Button(onClick = { manualError = null; showAdd = true }) {
                                Text(stringResource(R.string.remotes_empty_action_add))
                            }
                            TextButton(onClick = { importLauncher.launch("*/*") }) {
                                Text(stringResource(R.string.remotes_empty_action_import))
                            }
                        },
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(VirgaSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
                    ) {
                        items(state.remotes, key = { it.name }) { remote ->
                            // Key on quotaEpoch too: a pull-to-refresh bumps the epoch
                            // so this re-fires even though the card stays composed.
                            LaunchedEffect(remote.name, state.quotaEpoch) {
                                viewModel.fetchQuota(remote.name)
                            }
                            RemoteCard(
                                remote = remote,
                                onOpenBrowser = { onOpenBrowser(remote.name) },
                                onCreateTask = onCreateTask,
                                onDelete = { remoteToDelete = remote },
                                onTestConnectivity = { viewModel.testConnectivity(remote.name) },
                                onDedupe = { remoteToDedupe = remote },
                                onEdit = { viewModel.beginEditRemote(remote.name) },
                                onRename = { viewModel.beginRenameRemote(remote.name) },
                                onReauth = { viewModel.reauthRemote(remote.name) },
                                onSignOut = { viewModel.signOutRemote(remote.name) },
                                onExport = { viewModel.beginSingleRemoteExport(remote.name) },
                                quota = state.quotas[remote.name],
                                quotaLoading = remote.name in state.quotaLoading,
                                connectivity = state.connectivityResults[remote.name],
                                connectivityTesting = remote.name in state.connectivityTesting,
                            )
                        }
                    }
                }
            }
        }
    }

    remoteToDelete?.let { remote ->
        AlertDialog(
            onDismissRequest = { remoteToDelete = null },
            title = { Text(stringResource(R.string.remotes_delete_dialog_title)) },
            text = {
                Text(stringResource(R.string.remotes_delete_dialog_body, remote.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRemote(remote.name)
                        remoteToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.remotes_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { remoteToDelete = null }) {
                    Text(stringResource(R.string.remotes_delete_cancel))
                }
            },
        )
    }

    remoteToDedupe?.let { remote ->
        DedupeConfirmDialog(
            remoteName = remote.name,
            onConfirm = {
                viewModel.dedupeRemote(remote.name)
                remoteToDedupe = null
            },
            onDismiss = { remoteToDedupe = null },
        )
    }

    if (showExportDialog) {
        ExportConfigDialog(
            onConfirmEncrypted = { passphrase ->
                showExportDialog = false
                stagedExportPassphrase = passphrase
                stagedExportRedacted = false
                exportLauncher.launch("virga-config.virgaenc")
            },
            onConfirmRaw = {
                showExportDialog = false
                stagedExportPassphrase = null
                stagedExportRedacted = false
                exportLauncher.launch("rclone.conf")
            },
            onConfirmRedacted = {
                showExportDialog = false
                stagedExportPassphrase = null
                stagedExportRedacted = true
                exportLauncher.launch("rclone-redacted.conf")
            },
            onDismiss = { showExportDialog = false },
        )
    }

    uriToImport?.let { uri ->
        ImportModeDialog(
            onConfirmReplace = {
                viewModel.importConfigFromUri(uri, mergeMode = false)
                uriToImport = null
            },
            onConfirmMerge = {
                viewModel.importConfigFromUri(uri, mergeMode = true)
                uriToImport = null
            },
            onDismiss = { uriToImport = null },
        )
    }

    // Shown after the wipe-confirm when the selected file is an encrypted container.
    // The VM sets pendingEncryptedImport; wrong-passphrase feedback arrives via snackbar
    // while the dialog stays open so the user can retry.
    state.pendingEncryptedImport?.let { uri ->
        ImportPassphraseDialog(
            onConfirm = { passphrase -> viewModel.importConfigFromUri(uri, passphrase) },
            onDismiss = { viewModel.dismissImportPassphrase() },
        )
    }

    // Single-remote export dialog — shown when the overflow menu "Export this remote" is tapped.
    state.singleExportRemote?.let { remoteName ->
        // rclone allows ':' and other chars that are invalid in SAF document names;
        // sanitize for the suggested filename only (the dialog still shows the real name).
        val safeName = remoteName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        SingleRemoteExportDialog(
            remoteName = remoteName,
            onConfirmRaw = {
                viewModel.dismissSingleRemoteExport()
                stagedSingleExportRemote = remoteName
                stagedSingleExportRedacted = false
                singleRemoteExportLauncher.launch("$safeName.conf")
            },
            onConfirmRedacted = {
                viewModel.dismissSingleRemoteExport()
                stagedSingleExportRemote = remoteName
                stagedSingleExportRedacted = true
                singleRemoteExportLauncher.launch("$safeName-redacted.conf")
            },
            onDismiss = { viewModel.dismissSingleRemoteExport() },
        )
    }

    state.editMode?.let { editMode ->
        AddRemoteDialog(
            editMode = editMode,
            oauthProviders = emptyList(),
            error = manualError,
            customClientIds = state.customClientIds,
            onEnsureProviders = { viewModel.ensureProvidersLoaded() },
            allOptionsForBackend = { viewModel.allOptionsForBackend(it) },
            providersLoaded = providersLoaded,
            existingRemotes = state.remotes,
            onDismiss = { viewModel.dismissEditRemote(); manualError = null },
            onEditConfirm = { values ->
                viewModel.submitEdit(editMode.remoteName, values) { success, error ->
                    if (success) manualError = null else manualError = error
                }
            },
            onManualConfirm = { _, _, _ -> },
            onOAuth = { _, _ -> },
            onSaveClientId = { _, _ -> },
            onClearClientId = { },
        )
    }

    // Rename dialog — mutually exclusive with edit (beginRenameRemote clears editMode).
    state.renameTarget?.let { oldName ->
        RenameRemoteDialog(
            oldName = oldName,
            inFlight = state.renameInFlight,
            onConfirm = { newName, onError ->
                viewModel.submitRename(oldName, newName, onError)
            },
            onDismiss = { viewModel.dismissRenameRemote() },
        )
    }

    // Edit takes precedence: never stack the add + edit sheets if both are somehow set.
    if (showAdd && state.editMode == null) {
        AddRemoteDialog(
            oauthProviders = viewModel.oauthProviders,
            error = manualError,
            customClientIds = state.customClientIds,
            onEnsureProviders = viewModel::ensureProvidersLoaded,
            allOptionsForBackend = viewModel::allOptionsForBackend,
            providersLoaded = providersLoaded,
            pickerEntries = viewModel.pickerEntries(),
            setupKindFor = viewModel::setupKindFor,
            existingRemotes = state.remotes,
            oauthInProgress = state.oauthInProgress,
            daemonOAuthTokenPrompt = state.daemonOAuthTokenPrompt,
            daemonOAuthFieldPrompt = state.daemonOAuthFieldPrompt,
            onDismiss = { showAdd = false; manualError = null },
            onManualConfirm = { name, type, params ->
                manualError = null
                viewModel.addRemote(name, type, params) { success, error ->
                    if (success) showAdd = false else manualError = error
                }
            },
            onCryptConfirm = { name, baseRemote, basePath, password, salt ->
                manualError = null
                viewModel.createCrypt(name, baseRemote, basePath, password, salt) { success, error ->
                    if (success) showAdd = false else manualError = error
                }
            },
            onWrapperConfirm = { name, type, params ->
                manualError = null
                viewModel.addRemote(name, type, params) { success, error ->
                    if (success) showAdd = false else manualError = error
                }
            },
            onOAuth = { provider, name -> viewModel.startOAuth(provider, name) },
            onDaemonOAuth = { type, name, clientId, clientSecret ->
                viewModel.startDaemonOAuth(type, name, clientId, clientSecret)
            },
            onDaemonOAuthDesktop = { type, name, clientId, clientSecret ->
                viewModel.startDaemonOAuth(type, name, clientId, clientSecret, forcePasteToken = true)
            },
            onSubmitDaemonOAuthToken = viewModel::submitDaemonOAuthToken,
            onSubmitDaemonOAuthFieldAnswer = viewModel::submitDaemonOAuthFieldAnswer,
            onCancelDaemonOAuth = viewModel::cancelDaemonOAuth,
            onSaveClientId = viewModel::saveClientId,
            onClearClientId = viewModel::clearClientId,
            onSaveClientSecret = viewModel::saveClientSecret,
            onClearClientSecret = viewModel::clearClientSecret,
        )
    }
}

/**
 * Confirms a dedupe operation on [remoteName]. Destructive (removes duplicate files),
 * so the confirm button is error-tinted. 4 params — within Codacy's composable limit.
 */
@Composable
internal fun DedupeConfirmDialog(
    remoteName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remotes_dedupe_confirm_title, remoteName)) },
        text = { Text(stringResource(R.string.remotes_dedupe_confirm_body, remoteName)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.remotes_dedupe_confirm_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remotes_dedupe_cancel))
            }
        },
    )
}

/**
 * Confirms a config import, which REPLACES every existing remote (remotes absent
 * from the imported file are removed). Destructive, so the confirm button is
 * error-tinted, mirroring the delete-remote dialog. [onConfirm] runs the import;
 * [onDismiss] backs out. Internal (not private) so it can be exercised directly
 * in tests — the picker that opens it can't be driven under Robolectric.
 */
@Composable
internal fun ImportConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remotes_import_dialog_title)) },
        text = { Text(stringResource(R.string.remotes_import_dialog_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.remotes_import_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remotes_delete_cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "RemoteCard light", showBackground = true)
@Preview(name = "RemoteCard dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RemoteCardPreview() {
    Surface {
        RemoteCard(
            remote = Remote(name = "gdrive", type = "drive"),
            onOpenBrowser = { },
            onCreateTask = {},
            onDelete = {},
        )
    }
}
