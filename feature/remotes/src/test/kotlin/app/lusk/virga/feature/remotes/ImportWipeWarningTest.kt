package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lusk.virga.core.rclone.oauth.OAuthProviders
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tests for the A5a import-wipe-warning feature: the AlertDialog that appears
 * before `importConfigFromUri` runs, warning the user that all existing remotes
 * will be replaced.
 *
 * Coverage strategy
 * -----------------
 * The dialog is the production [ImportConfirmDialog] composable, rendered directly
 * via `composeRule.setContent` with mock callbacks — so these tests exercise real
 * code, not a copy. The `uriToImport` state that gates it is compose-local inside
 * [RemotesScreen] and the file picker can't be driven under Robolectric, so two
 * full-screen renders cover the surrounding new lines (the state declaration, the
 * launcher registration, and the false branch of `uriToImport?.let`).
 *
 * What IS covered
 * ---------------
 * - [ImportConfirmDialog] title/body/confirm/cancel text resolve from the real
 *   `strings.xml` (`remotes_import_dialog_*` + reused `remotes_delete_cancel`)
 * - Clicking Import calls `onConfirm` (not `onDismiss`); Cancel calls `onDismiss`
 *   (not `onConfirm`) — the production dialog's real button wiring
 * - `RemotesScreen` composes with the new state; the `uriToImport?.let` false path
 *
 * What is NOT covered (and why)
 * -----------------------------
 * - The `uriToImport = uri` line in the `GetContent` lambda and the confirm
 *   lambda body (`importConfigFromUri(uri); uriToImport = null`) in [RemotesScreen]
 *   — both are reachable only after the system picker delivers a result, which
 *   can't be driven under Robolectric. The VM call itself is covered by the
 *   existing `RemotesViewModelTest.importConfigFromUri` suite, and the dialog's
 *   button→callback wiring is covered here against the production composable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportWipeWarningTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Renders the production [ImportConfirmDialog] with the given callbacks. */
    private fun renderImportDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ImportConfirmDialog(onConfirm = onConfirm, onDismiss = onDismiss)
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Builds a relaxed [RemotesViewModel] mock suitable for full-screen renders. */
    private fun fakeViewModel(uiState: RemotesUiState = RemotesUiState()): RemotesViewModel =
        mockk(relaxed = true) {
            every { this@mockk.uiState } returns MutableStateFlow(uiState)
            every { launchUrl } returns MutableStateFlow(null)
            every { providers } returns MutableStateFlow(null)
            every { oauthProviders } returns OAuthProviders.All
            every { pickerEntries() } returns null
        }

    // ---------------------------------------------------------------------------
    // Dialog tests — render the production ImportConfirmDialog directly
    // ---------------------------------------------------------------------------

    @Test
    fun importConfirmDialog_shouldDisplayWarningTitle() {
        renderImportDialog(onConfirm = {}, onDismiss = {})

        composeRule.onNodeWithText("Import config?").assertIsDisplayed()
    }

    @Test
    fun importConfirmDialog_shouldDisplayDestructiveBodyText() {
        renderImportDialog(onConfirm = {}, onDismiss = {})

        composeRule.onNodeWithText(
            "Importing replaces all of your current remotes with the ones in this file. " +
                "Remotes that aren't in the file will be removed. This can't be undone.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun importConfirmDialog_shouldDisplayImportConfirmButton() {
        renderImportDialog(onConfirm = {}, onDismiss = {})

        composeRule.onNodeWithText("Import").assertIsDisplayed()
    }

    @Test
    fun importConfirmDialog_shouldDisplayCancelButton() {
        renderImportDialog(onConfirm = {}, onDismiss = {})

        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun importConfirmDialog_shouldCallOnConfirm_whenImportButtonClicked() {
        val onConfirm: () -> Unit = mockk(relaxed = true)
        val onDismiss: () -> Unit = mockk(relaxed = true)
        renderImportDialog(onConfirm = onConfirm, onDismiss = onDismiss)

        composeRule.onNodeWithText("Import").performClick()

        verify(exactly = 1) { onConfirm() }
        verify(exactly = 0) { onDismiss() }
    }

    @Test
    fun importConfirmDialog_shouldCallOnDismiss_whenCancelButtonClicked() {
        val onConfirm: () -> Unit = mockk(relaxed = true)
        val onDismiss: () -> Unit = mockk(relaxed = true)
        renderImportDialog(onConfirm = onConfirm, onDismiss = onDismiss)

        composeRule.onNodeWithText("Cancel").performClick()

        verify(exactly = 1) { onDismiss() }
        verify(exactly = 0) { onConfirm() }
    }

    @Test
    fun importConfirmDialog_shouldNotCallOnConfirm_whenCancelButtonClicked() {
        val onConfirm: () -> Unit = mockk(relaxed = true)
        renderImportDialog(onConfirm = onConfirm, onDismiss = {})

        composeRule.onNodeWithText("Cancel").performClick()

        verify(exactly = 0) { onConfirm() }
    }

    @Test
    fun importConfirmDialog_shouldNotCallOnDismiss_whenImportButtonClicked() {
        val onDismiss: () -> Unit = mockk(relaxed = true)
        renderImportDialog(onConfirm = {}, onDismiss = onDismiss)

        composeRule.onNodeWithText("Import").performClick()

        verify(exactly = 0) { onDismiss() }
    }

    // ---------------------------------------------------------------------------
    // Full-screen render (approach a / coverage of RemotesScreen new lines)
    // ---------------------------------------------------------------------------

    /**
     * Renders the complete [RemotesScreen] via `composeRule.setContent` so that:
     *  - `var uriToImport by remember { mutableStateOf<Uri?>(null) }` is executed
     *  - The `importLauncher` registration is executed
     *  - The `uriToImport?.let { ... }` guard is evaluated (false branch — no uri)
     *
     * The screen composes without crash even though no Uri has been provided.
     */
    @Test
    fun remotesScreen_shouldCompose_withUriToImportDefaultNull() {
        val vm = fakeViewModel(RemotesUiState(remotes = emptyList()))
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
        // Screen renders: the import action button is present in the TopAppBar.
        composeRule.onNodeWithText("Import").assertIsDisplayed()
    }

    /**
     * Renders [RemotesScreen] with remotes present so the list path is taken.
     * The `uriToImport?.let` guard still evaluates to false (no uri picked),
     * covering the same new lines through a different state branch.
     */
    @Test
    fun remotesScreen_withRemotes_shouldCompose_withUriToImportDefaultNull() {
        val vm = fakeViewModel(
            RemotesUiState(
                remotes = listOf(
                    app.lusk.virga.core.common.model.Remote(name = "gdrive", type = "drive"),
                ),
            ),
        )
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
        composeRule.onNodeWithText("Import").assertIsDisplayed()
    }
}
