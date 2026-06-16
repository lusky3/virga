package app.lusk.virga.feature.stats

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.lusk.virga.core.common.model.LifetimeStats
import app.lusk.virga.core.common.model.RemoteStat
import app.lusk.virga.core.common.model.TaskStat
import app.lusk.virga.core.common.model.TrendDay
import app.lusk.virga.core.data.StatsRepository
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
 * Roborazzi golden-image test for [StatsScreen]. Mirrors the harness used across
 * the feature modules (see feature:sync): Robolectric on the JVM, NATIVE graphics,
 * a fixed device qualifier, goldens committed under `src/test/snapshots/`.
 *
 * Wrapped in [VirgaTheme] (not bare MaterialTheme) because the hero card reads the
 * Virga gradient/`LocalVirgaColors`, which throws without the Virga theme provider.
 *
 * The state is fully pinned: `firstSyncEpochMs` uses a fixed epoch that formats to
 * an absolute month ("January 2021"), and all other fields are constant, so the
 * capture is deterministic.
 *
 * Generate / refresh goldens:
 *   ./gradlew :feature:stats:recordRoborazziDebug
 * Verify against goldens:
 *   ./gradlew :feature:stats:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsScreenshotTest {

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

    private fun viewModel(
        stats: LifetimeStats = LifetimeStats(),
        remotes: List<RemoteStat> = emptyList(),
        tasks: List<TaskStat> = emptyList(),
        trend: List<TrendDay> = emptyList(),
    ): StatsViewModel {
        val repo: StatsRepository = mockk(relaxed = true) {
            every { this@mockk.stats } returns flowOf(stats)
            every { remoteStats } returns flowOf(remotes)
            every { taskStats } returns flowOf(tasks)
            every { trendFlow(any()) } returns flowOf(trend)
        }
        return StatsViewModel(repo)
    }

    @Test
    fun statsScreen_populated() {
        // firstSyncEpochMs fixed at 2021-01-01T00:00:00Z → "January 2021" (absolute).
        val stats = LifetimeStats(
            firstSyncEpochMs = 1_609_459_200_000L,
            totalRuns = 128,
            successfulRuns = 120,
            failedRuns = 8,
            totalFilesTransferred = 5_432,
            totalBytesTransferred = 12_700_000_000L,
            bytesUploaded = 8_300_000_000L,
            bytesDownloaded = 3_200_000_000L,
            bytesTwoWay = 1_200_000_000L,
            totalSyncMillis = 13_320_000L,
            largestRunBytes = 2_400_000_000L,
            longestRunMillis = 2_640_000L,
            currentStreakDays = 5,
            longestStreakDays = 14,
        )
        val vm = viewModel(stats = stats)
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StatsScreen(onBack = {}, viewModel = vm)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun statsScreen_withRemoteStats() {
        val stats = LifetimeStats(totalRuns = 5, totalBytesTransferred = 1024)
        val remotes = listOf(RemoteStat("gdrive", 5, 4, 1024, 12))
        val today = System.currentTimeMillis() / 86_400_000L
        val trend = (0 until 30).map { i ->
            TrendDay(dayOffset = (today - 29 + i).toInt(), bytes = if (i > 25) 1000L * (i - 25) else 0L)
        }
        val vm = viewModel(stats = stats, remotes = remotes, trend = trend)
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StatsScreen(onBack = {}, viewModel = vm)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }
}
