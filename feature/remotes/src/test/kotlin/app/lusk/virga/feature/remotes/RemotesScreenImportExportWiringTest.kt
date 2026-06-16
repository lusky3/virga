package app.lusk.virga.feature.remotes

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.rclone.oauth.OAuthProviders
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screen-level wiring tests verifying that [RemotesScreen] shows the correct dialogs
 * when [RemotesUiState.singleExportRemote] and [RemotesUiState.pendingEncryptedImport]
 * are set. These tests drive the production composable code paths that the unit-only
 * ViewModel tests cannot reach (the `state.singleExportRemote?.let` and the
 * `uriToImport?.let` blocks in RemotesScreen).
 *
 * Because the dialogs rendered here have NO [PasswordVisualTransformation] fields
 * the standard [createComposeRule] + [waitForIdle] approach is used throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemotesScreenImportExportWiringTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun fakeViewModel(uiState: RemotesUiState = RemotesUiState()): RemotesViewModel =
        mockk(relaxed = true) {
            every { this@mockk.uiState } returns MutableStateFlow(uiState)
            every { launchUrl } returns MutableStateFlow(null)
            every { providers } returns MutableStateFlow(null)
            every { oauthProviders } returns OAuthProviders.All
            every { pickerEntries() } returns null
        }

    private fun renderScreen(vm: RemotesViewModel) {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RemotesScreen(onOpenBrowser = {}, viewModel = vm)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    // --- SingleRemoteExportDialog wiring -----------------------------------------

    /**
     * When [RemotesUiState.singleExportRemote] is non-null the screen renders
     * [SingleRemoteExportDialog] with the remote name in the title.
     */
    @Test
    fun remotesScreen_shouldShowSingleRemoteExportDialog_whenSingleExportRemoteSet() {
        val vm = fakeViewModel(
            RemotesUiState(
                remotes = listOf(Remote(name = "gdrive", type = "drive")),
                singleExportRemote = "gdrive",
            ),
        )
        renderScreen(vm)
        composeRule.onNodeWithText("Export \"gdrive\"").assertIsDisplayed()
    }

    @Test
    fun remotesScreen_shouldNotShowSingleRemoteExportDialog_whenSingleExportRemoteNull() {
        val vm = fakeViewModel(
            RemotesUiState(
                remotes = listOf(Remote(name = "gdrive", type = "drive")),
                singleExportRemote = null,
            ),
        )
        renderScreen(vm)
        composeRule.onNodeWithText("Export \"gdrive\"", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun remotesScreen_singleExportDialog_shouldDisplayRawAndRedactedOptions() {
        val vm = fakeViewModel(
            RemotesUiState(singleExportRemote = "mysftp"),
        )
        renderScreen(vm)
        composeRule.onNodeWithText("Raw — include credentials").assertIsDisplayed()
        composeRule.onNodeWithText("Redacted — structure only, secrets hidden").assertIsDisplayed()
    }

    // --- ImportModeDialog screen wiring ------------------------------------------

    /**
     * The [RemotesScreen] toolbar has the Import action button regardless of remotes list.
     * This confirms the toolbar action text is present (covering the toolbar branch).
     */
    @Test
    fun remotesScreen_shouldDisplayImportToolbarAction() {
        val vm = fakeViewModel(RemotesUiState(remotes = emptyList()))
        renderScreen(vm)
        composeRule.onNodeWithText("Import").assertIsDisplayed()
    }

    @Test
    fun remotesScreen_shouldDisplayExportConfigToolbarAction() {
        val vm = fakeViewModel(RemotesUiState(remotes = emptyList()))
        renderScreen(vm)
        composeRule.onNodeWithText("Export config").assertIsDisplayed()
    }

    /**
     * When [RemotesUiState.singleExportRemote] is set to a different remote name,
     * the dialog title reflects that remote name precisely.
     */
    @Test
    fun remotesScreen_singleExportDialog_titleContainsCorrectRemoteName() {
        val vm = fakeViewModel(
            RemotesUiState(singleExportRemote = "backblaze-b2"),
        )
        renderScreen(vm)
        composeRule.onNodeWithText("Export \"backblaze-b2\"").assertIsDisplayed()
    }

    // --- ImportPassphraseDialog screen wiring ------------------------------------

    /**
     * When [RemotesUiState.pendingEncryptedImport] is non-null the screen renders
     * [ImportPassphraseDialog]. The dialog's [PasswordVisualTransformation] cursor-blink
     * loop blocks Espresso's idle check in the popup window, so [setContent]'s internal
     * [waitForIdle] must be called before the dialog is opened. We start with no pending
     * import, let [setContent] idle cleanly, then emit the state with the pending URI so
     * the popup opens — and pump frames manually to drive its composition.
     *
     * This test reaches the `state.pendingEncryptedImport?.let` branch in RemotesScreen.
     */
    @Test
    fun remotesScreen_shouldComposeWithPendingEncryptedImportSet() {
        val uri: Uri = mockk()
        val stateFlow = MutableStateFlow(RemotesUiState())
        val vm: RemotesViewModel = mockk(relaxed = true) {
            every { this@mockk.uiState } returns stateFlow
            every { launchUrl } returns MutableStateFlow(null)
            every { providers } returns MutableStateFlow(null)
            every { oauthProviders } returns OAuthProviders.All
            every { pickerEntries() } returns null
        }

        // Step 1: compose without the dialog open so setContent's waitForIdle passes.
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RemotesScreen(onOpenBrowser = {}, viewModel = vm)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Step 2: emit the encrypted-import pending state so the dialog opens in a
        // popup window, then pump frames to drive its composition without waitForIdle.
        composeRule.runOnUiThread {
            stateFlow.value = RemotesUiState(pendingEncryptedImport = uri)
            repeat(20) { composeRule.mainClock.advanceTimeByFrame() }
        }
    }
}
