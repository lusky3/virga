package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.designsystem.theme.VirgaTheme
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
 * Roborazzi golden-image test for [RunDetailScreen]. Runs entirely on the JVM via
 * Robolectric; captures a PNG and compares against the committed golden at
 * `src/test/snapshots/`.
 *
 * The screen is rendered standalone (no NavDisplay wrapper), so
 * `LocalSharedTransitionScope` is null and the screen takes its plain, non-animated
 * path — it never reads `LocalNavAnimatedContentScope` (which would throw here).
 *
 * Generate / refresh goldens:
 *   ./gradlew :feature:sync:recordRoborazziDebug
 * Verify against goldens (CI default; fails on visual diff):
 *   ./gradlew :feature:sync:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RunDetailScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
        options = RoborazziRule.Options(outputDirectoryPath = "src/test/snapshots"),
    )

    @Test
    fun runDetailScreen_succeeded() {
        // Epoch-0-based timestamps keep the formatted dates stable per run. NOTE:
        // Robolectric inherits the host JVM time zone (it does NOT pin one), so this
        // golden — like every dated golden in the repo — assumes the verifying host
        // matches the recording host's zone. A repo-wide fix would pin user.timezone
        // (e.g. UTC) for unit tests and re-record all goldens; tracked as a follow-up.
        val run = SyncRun(
            id = 10L,
            taskId = 1L,
            startedAtEpochMs = 0L,
            endedAtEpochMs = 65_000L,
            status = SyncStatus.SUCCESS,
            filesTransferred = 12,
            bytesTransferred = 5_368_709L,
            errorCount = 0,
            logPath = "/data/logs/run-10.log",
        )
        val historyRepo: SyncHistoryRepository = mockk(relaxed = true) {
            every { observeRun(any()) } returns flowOf(run)
        }
        val taskRepo: SyncTaskRepository = mockk(relaxed = true) {
            every { task(any()) } returns flowOf(
                SyncTask(
                    id = 1L,
                    name = "DCIM backup",
                    sourcePath = "/storage/emulated/0/DCIM",
                    remoteName = "gdrive",
                    remotePath = "/Backup/DCIM",
                    direction = SyncDirection.UPLOAD,
                    intervalMinutes = 60,
                ),
            )
        }
        val viewModel = RunDetailViewModel(historyRepo, taskRepo)
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RunDetailScreen(runId = 10L, onBack = {}, viewModel = viewModel)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun runDetailScreen_withFailedFiles() {
        // B9: verifies that the "Failed files" section renders when SyncRun.failedFiles
        // is non-empty. Each entry is a "path\terror" line; the section must show the
        // path and the error message under the FailedFilesSection composable.
        val run = SyncRun(
            id = 11L,
            taskId = 1L,
            startedAtEpochMs = 0L,
            endedAtEpochMs = 30_000L,
            status = SyncStatus.SUCCESS,
            filesTransferred = 4,
            bytesTransferred = 1_048_576L,
            errorCount = 2,
            failedFiles = "docs/report.pdf\tpermission denied\nphotos/img.jpg\ttimeout",
        )
        val historyRepo: SyncHistoryRepository = mockk(relaxed = true) {
            every { observeRun(any()) } returns flowOf(run)
        }
        val taskRepo: SyncTaskRepository = mockk(relaxed = true) {
            every { task(any()) } returns flowOf(
                SyncTask(
                    id = 1L,
                    name = "Docs backup",
                    sourcePath = "/sdcard/Docs",
                    remoteName = "gdrive",
                    remotePath = "/Backup/Docs",
                    direction = SyncDirection.UPLOAD,
                    intervalMinutes = null,
                ),
            )
        }
        val viewModel = RunDetailViewModel(historyRepo, taskRepo)
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RunDetailScreen(runId = 11L, onBack = {}, viewModel = viewModel)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }
}
