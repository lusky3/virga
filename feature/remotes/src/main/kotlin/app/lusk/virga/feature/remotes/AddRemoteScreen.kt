package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.feature.remotes.oauth.launchCustomTab

/**
 * Standalone "add a cloud account" destination — the same [AddRemoteDialog] the
 * Remotes tab uses, but as its own screen so flows that need a remote (the first-sync
 * wizard, the task editor) can push it onto their OWN back stack and return here when
 * done, instead of switching to the Remotes tab (which forced a second "add" tap and
 * stranded the caller). Reuses [RemotesViewModel] so the OAuth / crypt / manual / BYO
 * paths and the custom-tab handoff behave identically to the Remotes screen.
 *
 * [onDone] is invoked exactly once when a remote is added (manual, crypt, or the async
 * OAuth round-trip — detected by the remote count increasing) or when the user dismisses
 * the sheet; the caller pops back to wherever it pushed this from.
 */
@Composable
fun AddRemoteScreen(
    onDone: () -> Unit,
    viewModel: RemotesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launchUrl by viewModel.launchUrl.collectAsStateWithLifecycle()
    val providersLoaded by viewModel.providers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var manualError by remember { mutableStateOf<String?>(null) }

    // Any increase over the count when we arrived means a remote was added — this is the
    // one signal that also covers the asynchronous OAuth completion (which has no inline
    // success callback). Guarded so onDone fires once.
    val initialCount = remember { state.remotes.size }
    var finished by remember { mutableStateOf(false) }
    fun finishOnce() {
        if (!finished) {
            finished = true
            onDone()
        }
    }

    LaunchedEffect(state.remotes.size) {
        if (state.remotes.size > initialCount) finishOnce()
    }
    // Launching the OAuth custom tab does NOT dismiss the sheet (it's just another
    // activity on top); the sheet stays so the user returns to it, and the count-increase
    // above pops back on success.
    LaunchedEffect(launchUrl) {
        launchUrl?.let { launchCustomTab(context, it); viewModel.onLaunchUrlConsumed() }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
            onDismiss = { finishOnce() },
            onManualConfirm = { name, type, params ->
                manualError = null
                viewModel.addRemote(name, type, params) { success, error ->
                    if (success) finishOnce() else manualError = error
                }
            },
            onCryptConfirm = { name, baseRemote, basePath, password, salt ->
                manualError = null
                viewModel.createCrypt(name, baseRemote, basePath, password, salt) { success, error ->
                    if (success) finishOnce() else manualError = error
                }
            },
            onOAuth = { provider, name -> viewModel.startOAuth(provider, name) },
            onSaveClientId = viewModel::saveClientId,
            onClearClientId = viewModel::clearClientId,
        )
    }
}
