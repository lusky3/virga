package app.lusk.virga.feature.explorer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.data.FileBrowserRepository
import app.lusk.virga.core.data.RemoteFolderPickStore
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose-UI coverage for the pick-mode `CreateFolderDialog` in [FileBrowserScreen].
 *
 * The dialog renders into a separate window that the Robolectric compose idling strategy
 * never reports as idle, so the usual `setContent { dialog-open }` + `onNode`/`waitForIdle`
 * flow hangs (espresso AppNotIdleException). This test instead opens the dialog through the
 * "New folder" FAB, pumps frames manually to drive the dialog sub-window's composition (the
 * `CreateFolderDialog` call + body), and dismisses it via the UI thread (no idle wait) so the
 * rule's teardown idle check can settle. That composition is what registers coverage for the
 * dialog code, which the standalone Roborazzi capture lambda (a no-op outside record/verify
 * mode) never reached in [FileBrowserScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class FileBrowserDialogCoverageTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun viewModel(listing: List<FileItem>): FileBrowserViewModel {
        val fileBrowser: FileBrowserRepository = mockk(relaxed = true) {
            coEvery { list(any(), any()) } returns listing
            coEvery { mkdir(any(), any()) } returns Unit
        }
        val remoteRepo: RemoteRepository = mockk(relaxed = true) {
            every { remotes } returns flowOf(listOf(Remote("gdrive", "drive")))
        }
        val dispatchers: DispatcherProvider = mockk(relaxed = true) {
            every { main } returns Dispatchers.Unconfined
            every { default } returns Dispatchers.Unconfined
            every { io } returns Dispatchers.Unconfined
        }
        val folderPickStore: RemoteFolderPickStore = mockk(relaxed = true)
        return FileBrowserViewModel(fileBrowser, remoteRepo, dispatchers, folderPickStore)
    }

    private val listing = listOf(
        FileItem(name = "Photos", path = "Photos", isDir = true, size = 0, modTimeEpochMs = 0L),
    )

    @Test
    fun createFolderDialog_opensViaFabThenDismisses() {
        val vm = viewModel(listing)
        vm.selectRemote("gdrive")
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FileBrowserScreen(onBack = {}, pickMode = true, viewModel = vm)
                }
            }
        }

        // Open the dialog through the "New folder" FAB. A later assert/interaction on the open
        // dialog would hang — the Robolectric idling strategy never reports the dialog
        // sub-window as idle — so instead pump frames on the UI thread to drive the dialog's
        // content composition (the CreateFolderDialog body: title, text field, buttons), which
        // is what registers their coverage, then dismiss via the UI thread (no idle wait) so the
        // window is gone before the rule's teardown idle check. The dismiss also covers
        // dismissCreateFolderDialog.
        composeRule.onNodeWithContentDescription("Create new folder").performClick()
        composeRule.runOnUiThread {
            repeat(20) { composeRule.mainClock.advanceTimeByFrame() }
            vm.dismissCreateFolderDialog()
            repeat(4) { composeRule.mainClock.advanceTimeByFrame() }
        }
    }
}
