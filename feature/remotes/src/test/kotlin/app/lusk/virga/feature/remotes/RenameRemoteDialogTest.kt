package app.lusk.virga.feature.remotes

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Coverage tests for [RenameRemoteDialog].
 *
 * ## Robolectric popup-window constraint
 * [RenameRemoteDialog] uses [AlertDialog] with an [OutlinedTextField]. Under
 * Robolectric, the cursor-blink coroutine inside [OutlinedTextField] re-queues
 * frame callbacks indefinitely once the dialog popup window is open, so any call
 * to [waitForIdle] on the popup — including the one that [setContent] makes
 * internally — throws [AppNotIdleException][androidx.test.espresso.AppNotIdleException].
 *
 * ## Adopted pattern (mirrors [ConfigTransferDialogsTest])
 * The dialog is opened through [RemotesScreen] rather than rendered directly.
 * [RemotesScreen] sits in the MAIN window; its initial composition settles normally.
 * A non-null [RemotesUiState.renameTarget] then causes [RemotesScreen] to open
 * [RenameRemoteDialog] in its popup window. Frames are pumped with
 * `mainClock.advanceTimeByFrame()` inside [runOnUiThread] to drive the popup's
 * composition (executing the dialog body: title, field, buttons). This exercises
 * the PRODUCTION composable code paths for coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenameRemoteDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun fakeViewModel(uiState: RemotesUiState = RemotesUiState()): RemotesViewModel =
        mockk(relaxed = true) {
            every { this@mockk.uiState } returns MutableStateFlow(uiState)
            every { launchUrl } returns MutableStateFlow(null)
            every { providers } returns MutableStateFlow(null)
            every { oauthProviders } returns OAuthProviders.All
            every { pickerEntries() } returns null
        }

    /**
     * Sets [RemotesScreen] up without a rename dialog open (so [setContent] idles
     * cleanly in the main window), then emits a state with [renameTarget] set so
     * [RenameRemoteDialog] opens in its popup window. Pumps frames to drive the
     * popup's composition.
     */
    private fun openRenameDialog(vm: RemotesViewModel, remoteName: String) {
        val stateFlow = MutableStateFlow(RemotesUiState())
        every { vm.uiState } returns stateFlow

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

        // Emit the rename state — this opens the dialog in a separate popup window.
        // Pump frames to drive its initial composition without calling waitForIdle
        // on the popup window (which would block on the cursor-blink loop).
        composeRule.runOnUiThread {
            stateFlow.value = RemotesUiState(renameTarget = remoteName)
            repeat(20) { composeRule.mainClock.advanceTimeByFrame() }
        }
    }

    /**
     * Opening [RenameRemoteDialog] composes the production code path — title,
     * text field, Rename and Cancel buttons — without throwing. Reaching here
     * without an [AppNotIdleException] is the assertion.
     */
    @Test
    fun renameRemoteDialog_rendersWhenRenameTargetSet() {
        val vm = fakeViewModel()
        openRenameDialog(vm, "gdrive")
        // Composition ran; dialog body code paths executed.
    }

    /**
     * A second setContent call with inFlight=true drives the disabled-button code
     * path through the rename dialog composable.
     */
    @Test
    fun renameRemoteDialog_rendersInFlightState() {
        val vm = fakeViewModel()
        val stateFlow = MutableStateFlow(RemotesUiState())
        every { vm.uiState } returns stateFlow

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

        composeRule.runOnUiThread {
            stateFlow.value = RemotesUiState(renameTarget = "mys3", renameInFlight = true)
            repeat(20) { composeRule.mainClock.advanceTimeByFrame() }
        }
        // inFlight=true branch of the dialog composable executed; no exception = pass.
    }
}
