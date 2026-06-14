package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.data.RemoteFolderPickStore
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.sync.SyncScheduler
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi golden-image tests. Runs entirely on the JVM via Robolectric;
 * captures PNGs of key Compose screens and compares against committed
 * goldens at `src/test/snapshots/`.
 *
 * Generate / refresh goldens:
 *   ./gradlew :feature:sync:recordRoborazziDebug
 * Verify against goldens (CI default; fails on visual diff):
 *   ./gradlew :feature:sync:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncTaskEditScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
        options = RoborazziRule.Options(
            outputDirectoryPath = "src/test/snapshots",
            // Verify mode is the CI default — fails on pixel diff. Switch to
            // RECORD via -Proborazzi.test.record=true to regenerate goldens.
        ),
    )

    private val taskRepository: SyncTaskRepository = mockk(relaxed = true)
    private val remoteRepository: RemoteRepository = mockk {
        every { remotes } returns flowOf(
            listOf(
                Remote(name = "gdrive", type = "drive"),
                Remote(name = "box", type = "box"),
            ),
        )
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)

    @Test
    fun syncTaskEditScreen_emptyForm() {
        val viewModel = SyncTaskEditViewModel(taskRepository, remoteRepository, scheduler, RemoteFolderPickStore(), PendingRemoteResult(), prefsRepo())
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SyncTaskEditScreen(taskId = 0L, onBack = {}, viewModel = viewModel)
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun syncTaskEditScreen_filledForm() {
        val viewModel = SyncTaskEditViewModel(taskRepository, remoteRepository, scheduler, RemoteFolderPickStore(), PendingRemoteResult(), prefsRepo())
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncTaskEditScreen(taskId = 0L, onBack = {}, viewModel = viewModel)
                }
            }
        }
        viewModel.update {
            it.copy(
                name = "DCIM backup",
                sourcePath = "/storage/emulated/0/DCIM",
                remoteName = "gdrive",
                remotePath = "/Backup/DCIM",
                intervalMinutes = 60,
            )
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    /**
     * Expands the collapsible Advanced section so its bandwidth/buffer fields
     * actually compose — exercising the extracted `ValidatedAsciiField` helper,
     * which stays unrendered (and uncovered) while the section is collapsed.
     */
    @Test
    fun syncTaskEditScreen_advancedExpanded_rendersValidatedFields() {
        // Advanced options are gated behind a preference; enable it so the
        // AdvancedSection (and its toggle) actually compose.
        val advancedPrefs: PreferencesRepository = mockk(relaxed = true) {
            every { preferences } returns flowOf(AppPreferences(showAdvancedOptions = true))
        }
        val viewModel = SyncTaskEditViewModel(taskRepository, remoteRepository, scheduler, RemoteFolderPickStore(), PendingRemoteResult(), advancedPrefs)
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SyncTaskEditScreen(taskId = 0L, onBack = {}, viewModel = viewModel)
                    }
                }
            }
        }
        // The Advanced toggle reads "Show" when collapsed; tapping it composes the
        // bandwidth/buffer fields (the extracted ValidatedAsciiField).
        composeRule.waitForIdle()
        // The toggle sits near the bottom of a long scrollable form; scroll it into
        // view so the tap actually lands, then expand to compose the bw/buffer fields.
        composeRule.onNodeWithText("Show").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Upload limit (Wi-Fi)").assertExists()
        composeRule.onRoot().captureRoboImage()
    }
}

private fun prefsRepo(): PreferencesRepository = mockk(relaxed = true) {
    every { preferences } returns flowOf(AppPreferences())
}
