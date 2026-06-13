package app.lusk.virga.feature.explorer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.data.FileBrowserRepository
import app.lusk.virga.core.data.RemoteFolderPickStore
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi golden-image tests for [FileBrowserScreen]. Mirrors the harness used
 * across the feature modules (see feature:sync): Robolectric on the JVM, NATIVE
 * graphics, a fixed device qualifier, goldens committed under `src/test/snapshots/`.
 *
 * The screen's content is driven entirely by the ViewModel state, so each test
 * builds a relaxed-MockK [FileBrowserViewModel] and primes it via `selectRemote`
 * (which lists through the mocked [FileBrowserRepository]) before composing. The
 * dispatcher provider returns [Dispatchers.Unconfined] so the listing coroutine
 * completes synchronously and the captured frame is the resolved state.
 *
 * Listings use fixed mod-times that format to absolute dates ("Jan 1, 2021"),
 * never relative spans, so captures are deterministic.
 *
 * Generate / refresh goldens:
 *   ./gradlew :feature:explorer:recordRoborazziDebug
 * Verify against goldens:
 *   ./gradlew :feature:explorer:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBrowserScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
        options = RoborazziRule.Options(
            outputDirectoryPath = "src/test/snapshots",
        ),
    )

    // Fixed epoch-ms (2021-01-01T00:00:00Z) → absolute date, deterministic.
    private val fixedModTime = 1_609_459_200_000L

    private val populatedListing = listOf(
        FileItem(name = "Photos", path = "Photos", isDir = true, size = 0, modTimeEpochMs = fixedModTime),
        FileItem(name = "Documents", path = "Documents", isDir = true, size = 0, modTimeEpochMs = fixedModTime),
        FileItem(name = "report.pdf", path = "report.pdf", isDir = false, size = 245_760, modTimeEpochMs = fixedModTime),
        FileItem(name = "budget.xlsx", path = "budget.xlsx", isDir = false, size = 98_765, modTimeEpochMs = fixedModTime),
        FileItem(name = "notes.txt", path = "notes.txt", isDir = false, size = 1_024, modTimeEpochMs = fixedModTime),
    )

    private fun viewModel(listing: List<FileItem>): FileBrowserViewModel {
        val fileBrowser: FileBrowserRepository = mockk(relaxed = true) {
            coEvery { list(any(), any()) } returns listing
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

    @Test
    fun fileBrowserScreen_populated() {
        val vm = viewModel(populatedListing)
        // Select a remote so the screen renders the directory listing (not the
        // RemotePicker) — the mocked repo resolves the list synchronously.
        vm.selectRemote("gdrive")
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FileBrowserScreen(onBack = {}, viewModel = vm)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun fileBrowserScreen_empty() {
        val vm = viewModel(emptyList())
        vm.selectRemote("gdrive")
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FileBrowserScreen(onBack = {}, viewModel = vm)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }
}
