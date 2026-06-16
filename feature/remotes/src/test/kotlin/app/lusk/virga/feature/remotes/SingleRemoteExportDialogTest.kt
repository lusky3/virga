package app.lusk.virga.feature.remotes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render and interaction tests for [SingleRemoteExportDialog].
 *
 * No password fields → no cursor-blink idle issue; all button interactions
 * are exercised against the production composable directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SingleRemoteExportDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun renderDialog(
        remoteName: String = "gdrive",
        onConfirmRaw: () -> Unit = {},
        onConfirmRedacted: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SingleRemoteExportDialog(
                        remoteName = remoteName,
                        onConfirmRaw = onConfirmRaw,
                        onConfirmRedacted = onConfirmRedacted,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun singleRemoteExportDialog_shouldDisplayTitleWithRemoteName() {
        renderDialog(remoteName = "mysftp")
        composeRule.onNodeWithText("Export \"mysftp\"").assertIsDisplayed()
    }

    @Test
    fun singleRemoteExportDialog_shouldDisplayRawOption() {
        renderDialog()
        composeRule.onNodeWithText("Raw — include credentials").assertIsDisplayed()
    }

    @Test
    fun singleRemoteExportDialog_shouldDisplayRedactedOption() {
        renderDialog()
        composeRule.onNodeWithText("Redacted — structure only, secrets hidden").assertIsDisplayed()
    }

    @Test
    fun singleRemoteExportDialog_shouldDisplayExportButton() {
        renderDialog()
        composeRule.onNodeWithText("Export").assertIsDisplayed()
    }

    @Test
    fun singleRemoteExportDialog_shouldDisplayCancelButton() {
        renderDialog()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun singleRemoteExportDialog_shouldShowRawWarning_byDefault() {
        renderDialog()
        composeRule.onNodeWithText(
            "The exported file contains your credentials",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun singleRemoteExportDialog_shouldShowRedactedInfo_whenRedactedSelected() {
        renderDialog()
        composeRule.onNodeWithText("Redacted — structure only, secrets hidden").performClick()
        composeRule.onNodeWithText(
            "Passwords, tokens, and secrets will be replaced",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun singleRemoteExportDialog_shouldCallOnConfirmRaw_whenExportClickedInDefaultState() {
        val onConfirmRaw: () -> Unit = mockk(relaxed = true)
        val onConfirmRedacted: () -> Unit = mockk(relaxed = true)
        renderDialog(onConfirmRaw = onConfirmRaw, onConfirmRedacted = onConfirmRedacted)

        composeRule.onNodeWithText("Export").performClick()

        verify(exactly = 1) { onConfirmRaw() }
        verify(exactly = 0) { onConfirmRedacted() }
    }

    @Test
    fun singleRemoteExportDialog_shouldCallOnConfirmRedacted_whenRedactedSelectedThenExportClicked() {
        val onConfirmRaw: () -> Unit = mockk(relaxed = true)
        val onConfirmRedacted: () -> Unit = mockk(relaxed = true)
        renderDialog(onConfirmRaw = onConfirmRaw, onConfirmRedacted = onConfirmRedacted)

        composeRule.onNodeWithText("Redacted — structure only, secrets hidden").performClick()
        composeRule.onNodeWithText("Export").performClick()

        verify(exactly = 1) { onConfirmRedacted() }
        verify(exactly = 0) { onConfirmRaw() }
    }

    @Test
    fun singleRemoteExportDialog_shouldCallOnDismiss_whenCancelClicked() {
        val onDismiss: () -> Unit = mockk(relaxed = true)
        val onConfirmRaw: () -> Unit = mockk(relaxed = true)
        renderDialog(onConfirmRaw = onConfirmRaw, onDismiss = onDismiss)

        composeRule.onNodeWithText("Cancel").performClick()

        verify(exactly = 1) { onDismiss() }
        verify(exactly = 0) { onConfirmRaw() }
    }

    @Test
    fun singleRemoteExportDialog_shouldNotCallOnDismiss_whenExportClicked() {
        val onDismiss: () -> Unit = mockk(relaxed = true)
        renderDialog(onDismiss = onDismiss)

        composeRule.onNodeWithText("Export").performClick()

        verify(exactly = 0) { onDismiss() }
    }
}
