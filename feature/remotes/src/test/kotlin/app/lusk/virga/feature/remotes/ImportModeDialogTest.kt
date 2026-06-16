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
 * Render and interaction tests for [ImportModeDialog].
 *
 * The dialog is rendered directly via [createComposeRule] (no AlertDialog
 * + PasswordVisualTransformation, so no cursor-blink idle issue) and all
 * button interactions are exercised against the production composable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportModeDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun renderDialog(
        onConfirmReplace: () -> Unit = {},
        onConfirmMerge: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ImportModeDialog(
                        onConfirmReplace = onConfirmReplace,
                        onConfirmMerge = onConfirmMerge,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun importModeDialog_shouldDisplayTitle() {
        renderDialog()
        composeRule.onNodeWithText("Import config").assertIsDisplayed()
    }

    @Test
    fun importModeDialog_shouldDisplayReplaceOption() {
        renderDialog()
        composeRule.onNodeWithText("Replace — remove existing remotes").assertIsDisplayed()
    }

    @Test
    fun importModeDialog_shouldDisplayMergeOption() {
        renderDialog()
        composeRule.onNodeWithText("Merge — keep existing, add new").assertIsDisplayed()
    }

    @Test
    fun importModeDialog_shouldDisplayImportConfirmButton() {
        renderDialog()
        composeRule.onNodeWithText("Import").assertIsDisplayed()
    }

    @Test
    fun importModeDialog_shouldDisplayCancelButton() {
        renderDialog()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun importModeDialog_shouldShowReplaceWarningByDefault() {
        renderDialog()
        composeRule.onNodeWithText(
            "All current remotes will be replaced",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun importModeDialog_shouldShowMergeInfo_whenMergeSelected() {
        renderDialog()
        composeRule.onNodeWithText("Merge — keep existing, add new").performClick()
        composeRule.onNodeWithText(
            "Remotes in this file will be added",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun importModeDialog_shouldCallOnConfirmReplace_whenImportClickedInDefaultState() {
        val onConfirmReplace: () -> Unit = mockk(relaxed = true)
        val onConfirmMerge: () -> Unit = mockk(relaxed = true)
        renderDialog(onConfirmReplace = onConfirmReplace, onConfirmMerge = onConfirmMerge)

        composeRule.onNodeWithText("Import").performClick()

        verify(exactly = 1) { onConfirmReplace() }
        verify(exactly = 0) { onConfirmMerge() }
    }

    @Test
    fun importModeDialog_shouldCallOnConfirmMerge_whenMergeSelectedThenImportClicked() {
        val onConfirmReplace: () -> Unit = mockk(relaxed = true)
        val onConfirmMerge: () -> Unit = mockk(relaxed = true)
        renderDialog(onConfirmReplace = onConfirmReplace, onConfirmMerge = onConfirmMerge)

        composeRule.onNodeWithText("Merge — keep existing, add new").performClick()
        composeRule.onNodeWithText("Import").performClick()

        verify(exactly = 1) { onConfirmMerge() }
        verify(exactly = 0) { onConfirmReplace() }
    }

    @Test
    fun importModeDialog_shouldCallOnDismiss_whenCancelClicked() {
        val onDismiss: () -> Unit = mockk(relaxed = true)
        val onConfirmReplace: () -> Unit = mockk(relaxed = true)
        renderDialog(onConfirmReplace = onConfirmReplace, onDismiss = onDismiss)

        composeRule.onNodeWithText("Cancel").performClick()

        verify(exactly = 1) { onDismiss() }
        verify(exactly = 0) { onConfirmReplace() }
    }

    @Test
    fun importModeDialog_shouldNotCallOnDismiss_whenImportClicked() {
        val onDismiss: () -> Unit = mockk(relaxed = true)
        renderDialog(onDismiss = onDismiss)

        composeRule.onNodeWithText("Import").performClick()

        verify(exactly = 0) { onDismiss() }
    }
}
