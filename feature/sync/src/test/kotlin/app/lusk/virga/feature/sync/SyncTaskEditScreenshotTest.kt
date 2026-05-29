package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.RemoteEntity
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
                RemoteEntity(name = "gdrive", type = "drive", displayName = "Google Drive"),
                RemoteEntity(name = "box", type = "box", displayName = "Box"),
            ),
        )
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)

    @Test
    fun syncTaskEditScreen_emptyForm() {
        val viewModel = SyncTaskEditViewModel(taskRepository, remoteRepository, scheduler)
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
        val viewModel = SyncTaskEditViewModel(taskRepository, remoteRepository, scheduler)
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
}
