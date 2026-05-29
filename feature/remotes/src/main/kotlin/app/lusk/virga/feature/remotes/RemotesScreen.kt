package app.lusk.virga.feature.remotes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.rclone.oauth.OAuthProvider
import app.lusk.virga.core.ui.EmptyState
import android.content.res.Configuration
import app.lusk.virga.feature.remotes.oauth.launchCustomTab
import androidx.compose.material3.Surface
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotesScreen(
    onOpenBrowser: () -> Unit,
    viewModel: RemotesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launchUrl by viewModel.launchUrl.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var remoteToDelete by remember { mutableStateOf<RemoteEntity?>(null) }

    // When the VM produces a Custom Tabs URL, hand it off and clear the signal.
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
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            viewModel.importConfig(text) {}
        }
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
                    TextButton(onClick = onOpenBrowser) { Text(stringResource(R.string.remotes_action_browse)) }
                    TextButton(onClick = { importLauncher.launch("*/*") }) { Text(stringResource(R.string.remotes_action_import)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.remotes_cd_add))
            }
        },
    ) { padding ->
        // Show a top-bar progress indicator while an OAuth round-trip is in flight.
        if (state.oauthInProgress) {
            Column(Modifier.padding(padding)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.remotes_oauth_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                state.refreshing && state.remotes.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.remotes.isEmpty() -> {
                    EmptyState(title = stringResource(R.string.remotes_empty))
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.remotes, key = { it.name }) { remote ->
                            RemoteCard(
                                remote = remote,
                                onDelete = { remoteToDelete = remote },
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
                Text(stringResource(R.string.remotes_delete_dialog_body, remote.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRemote(remote.name)
                    remoteToDelete = null
                }) { Text(stringResource(R.string.remotes_delete_confirm)) }
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
            providers = viewModel.oauthProviders,
            onDismiss = { showAdd = false },
            onManualConfirm = { name, type, params ->
                viewModel.addRemote(name, type, params) { showAdd = false }
            },
            onOAuth = { provider, name -> viewModel.startOAuth(provider, name) },
        )
    }
}

@Composable
private fun RemoteCard(remote: RemoteEntity, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // Task #25c: ellipsis on long display names
                Text(
                    remote.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(remote.type, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remotes_cd_delete))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddRemoteDialog(
    providers: List<OAuthProvider>,
    onDismiss: () -> Unit,
    onManualConfirm: (name: String, type: String, params: String) -> Unit,
    onOAuth: (provider: OAuthProvider, name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var params by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remotes_add_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.remotes_add_field_name)) },
                    // Remote name and rclone backend type are case-sensitive
                    // identifiers — never auto-capitalize or auto-correct them.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                )

                Text(
                    stringResource(R.string.remotes_add_sign_in_with),
                    style = MaterialTheme.typography.labelLarge,
                )
                // Task #25a: FlowRow wraps chips instead of a clipping non-wrapping Row
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    providers.forEach { provider ->
                        AssistChip(
                            onClick = { onOAuth(provider, name) },
                            enabled = name.isNotBlank(),
                            label = { Text(provider.displayName) },
                        )
                    }
                }

                HorizontalDivider()
                Text(stringResource(R.string.remotes_add_or_manual), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = type,
                    onValueChange = { type = it },
                    label = { Text(stringResource(R.string.remotes_add_field_type)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                )
                OutlinedTextField(
                    value = params,
                    onValueChange = { params = it },
                    label = { Text(stringResource(R.string.remotes_add_field_params)) },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onManualConfirm(name, type, params) },
                enabled = name.isNotBlank() && type.isNotBlank(),
            ) { Text(stringResource(R.string.remotes_add_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.remotes_add_cancel)) } },
    )
}

// ---------------------------------------------------------------------------
// Previews (Task #26)
// ---------------------------------------------------------------------------

@Preview(name = "RemoteCard light", showBackground = true)
@Preview(name = "RemoteCard dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "RemoteCard fontScale=2", showBackground = true, fontScale = 2f)
@Composable
private fun RemoteCardPreview() {
    Surface {
        RemoteCard(
            remote = RemoteEntity(name = "gdrive", type = "drive", displayName = "Google Drive"),
            onDelete = {},
        )
    }
}
