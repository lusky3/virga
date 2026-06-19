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
    var finished by remember { mutableStateOf(false) }
    fun finishOnce() {
        if (!finished) {
            finished = true
            onDone()
        }
    }

    // Arm the baseline on the first REAL emission, not the first frame. uiState is
    // stateIn(WhileSubscribed) so the initial composition always sees remotes=emptyList()
    // before the flow delivers the loaded list; capturing the count then (latch == null)
    // means a user who already has ≥1 remote wouldn't get the screen popped out from
    // under them the moment the list loads.
    var initialCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.remotes.size) {
        val base = initialCount
        if (base == null) initialCount = state.remotes.size
        else if (state.remotes.size > base) finishOnce()
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
            oauthInProgress = state.oauthInProgress,
            daemonOAuthTokenPrompt = state.daemonOAuthTokenPrompt,
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
            onWrapperConfirm = { name, type, params ->
                manualError = null
                viewModel.addRemote(name, type, params) { success, error ->
                    if (success) finishOnce() else manualError = error
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
            onCancelDaemonOAuth = viewModel::cancelDaemonOAuth,
            onSaveClientId = viewModel::saveClientId,
            onClearClientId = viewModel::clearClientId,
            onSaveClientSecret = viewModel::saveClientSecret,
            onClearClientSecret = viewModel::clearClientSecret,
        )
    }
}
