package app.lusk.virga.feature.remotes

import android.content.res.Configuration
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
    var manualError by remember { mutableStateOf<String?>(null) }

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
        if (uri != null) viewModel.importConfigFromUri(uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
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
            Column(Modifier.padding(padding)) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = oauthProgressCd },
                )
                Text(
                    stringResource(R.string.remotes_oauth_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(horizontal = VirgaSpacing.md, vertical = VirgaSpacing.sm)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
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
                                quota = state.quotas[remote.name],
                                quotaLoading = remote.name in state.quotaLoading,
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

    if (showAdd) {
        AddRemoteDialog(
            oauthProviders = viewModel.oauthProviders,
            error = manualError,
            customClientIds = state.customClientIds,
            onEnsureProviders = viewModel::ensureProvidersLoaded,
            allOptionsForBackend = viewModel::allOptionsForBackend,
            providersLoaded = providersLoaded,
            existingRemotes = state.remotes,
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
            onOAuth = { provider, name -> viewModel.startOAuth(provider, name) },
            onSaveClientId = viewModel::saveClientId,
            onClearClientId = viewModel::clearClientId,
        )
    }
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
