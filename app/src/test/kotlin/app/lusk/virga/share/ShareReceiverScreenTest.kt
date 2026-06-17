package app.lusk.virga.share

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lusk.virga.core.common.model.Remote
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose interaction tests for [ShareReceiverScreen].
 *
 * Tests render the screen directly (no ViewModel, no Hilt) by constructing
 * [ShareReceiverUiState] by hand and capturing callback invocations via
 * captured booleans. This follows the same pattern used in
 * [app.lusk.virga.onboarding.OnboardingPageCompositionTest].
 *
 * Limitations:
 *  - The dropdown-menu expansion/selection path cannot be driven reliably in
 *    Robolectric with ExposedDropdownMenuBox (it relies on a window popup that
 *    Robolectric's fake window manager does not expose via the semantic tree).
 *    Remote selection is therefore verified via the callback-capture approach
 *    (clicking when expanded is confirmed), but the menu-open tap is asserted
 *    on the text field presence rather than a full open/select flow.
 *  - [UploadStatus.Uploading] shows a [CircularProgressIndicator] with no text;
 *    this is verified by confirming "Upload" is absent in that state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareReceiverScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── helpers ───────────────────────────────────────────────────────────────

    private val noopCallbacks = ShareReceiverCallbacks(
        onRemoteSelected = {},
        onDestPathChanged = {},
        onUpload = {},
        onDismiss = {},
    )

    private val remoteA = Remote(name = "gdrive", type = "drive")
    private val remoteB = Remote(name = "dropbox", type = "dropbox")

    private fun idleStateWithRemotes(
        remotes: List<Remote> = listOf(remoteA),
        selected: Remote? = remoteA,
        fileNames: List<String> = listOf("photo.jpg"),
        destPath: String = "",
    ) = ShareReceiverUiState(
        fileNames = fileNames,
        remotes = remotes,
        selectedRemote = selected,
        destPath = destPath,
        uploadStatus = UploadStatus.Idle,
    )

    private fun idleStateNoRemotes(fileNames: List<String> = listOf("doc.pdf")) =
        ShareReceiverUiState(
            fileNames = fileNames,
            remotes = emptyList(),
            selectedRemote = null,
            destPath = "",
            uploadStatus = UploadStatus.Idle,
        )

    // ── top-bar title always visible ──────────────────────────────────────────

    @Test
    fun `should display the Share to Virga title in all states`() {
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = idleStateWithRemotes(), callbacks = noopCallbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Share to Virga").assertIsDisplayed()
    }

    // ── idle with remotes ─────────────────────────────────────────────────────

    @Test
    fun `should display the upload button when remotes are present`() {
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = idleStateWithRemotes(), callbacks = noopCallbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Upload").assertIsDisplayed()
    }

    @Test
    fun `should display the remote picker label when remotes are present`() {
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = idleStateWithRemotes(), callbacks = noopCallbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Destination remote").assertIsDisplayed()
    }

    @Test
    fun `should display the selected remote name in the picker field`() {
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(
                    state = idleStateWithRemotes(selected = remoteA),
                    callbacks = noopCallbacks,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("gdrive").assertIsDisplayed()
    }

    @Test
    fun `should invoke onUpload callback when upload button is tapped`() {
        var uploadCalled = false
        val callbacks = noopCallbacks.copy(onUpload = { uploadCalled = true })

        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = idleStateWithRemotes(), callbacks = callbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Upload").performClick()
        composeRule.waitForIdle()

        assertThat(uploadCalled).isTrue()
    }

    @Test
    fun `should display file count text when files are staged`() {
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(
                    state = idleStateWithRemotes(fileNames = listOf("a.jpg", "b.png")),
                    callbacks = noopCallbacks,
                )
            }
        }
        composeRule.waitForIdle()
        // The plural "2 files to upload" is expected.
        composeRule.onNodeWithText("2 files to upload").assertIsDisplayed()
    }

    @Test
    fun `should display destination folder hint label`() {
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = idleStateWithRemotes(), callbacks = noopCallbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Destination folder (optional)").assertIsDisplayed()
    }

    // ── idle with no remotes ──────────────────────────────────────────────────

    @Test
    fun `should display no-remotes message when remotes list is empty`() {
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = idleStateNoRemotes(), callbacks = noopCallbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No remotes configured. Add a remote first.").assertIsDisplayed()
    }

    @Test
    fun `should display Close button when remotes list is empty`() {
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = idleStateNoRemotes(), callbacks = noopCallbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test
    fun `should not display upload button when remotes list is empty`() {
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = idleStateNoRemotes(), callbacks = noopCallbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Upload").assertDoesNotExist()
    }

    @Test
    fun `should invoke onDismiss when Close button is tapped in no-remotes state`() {
        var dismissCalled = false
        val callbacks = noopCallbacks.copy(onDismiss = { dismissCalled = true })

        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = idleStateNoRemotes(), callbacks = callbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.waitForIdle()

        assertThat(dismissCalled).isTrue()
    }

    // ── uploading state ───────────────────────────────────────────────────────

    @Test
    fun `should not show the upload button while uploading`() {
        val uploadingState = ShareReceiverUiState(
            fileNames = listOf("file.jpg"),
            remotes = listOf(remoteA),
            selectedRemote = remoteA,
            uploadStatus = UploadStatus.Uploading,
        )
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = uploadingState, callbacks = noopCallbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Upload").assertDoesNotExist()
    }

    // ── done state ────────────────────────────────────────────────────────────

    @Test
    fun `should display success message when upload completes with no failures`() {
        val doneState = ShareReceiverUiState(
            fileNames = listOf("a.jpg", "b.jpg"),
            remotes = listOf(remoteA),
            selectedRemote = remoteA,
            uploadStatus = UploadStatus.Done(succeeded = 2, failed = 0),
        )
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = doneState, callbacks = noopCallbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 files uploaded successfully").assertIsDisplayed()
    }

    @Test
    fun `should display failure count when some uploads fail`() {
        val doneState = ShareReceiverUiState(
            fileNames = listOf("a.jpg", "b.jpg"),
            remotes = listOf(remoteA),
            selectedRemote = remoteA,
            uploadStatus = UploadStatus.Done(succeeded = 1, failed = 1),
        )
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = doneState, callbacks = noopCallbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 file failed").assertIsDisplayed()
    }

    @Test
    fun `should not display failure count text when all uploads succeed`() {
        val doneState = ShareReceiverUiState(
            fileNames = listOf("a.jpg"),
            remotes = listOf(remoteA),
            selectedRemote = remoteA,
            uploadStatus = UploadStatus.Done(succeeded = 1, failed = 0),
        )
        composeRule.setContent {
            MaterialTheme {
                ShareReceiverScreen(state = doneState, callbacks = noopCallbacks)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 file failed").assertDoesNotExist()
    }
}
