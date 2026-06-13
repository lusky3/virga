package app.lusk.virga.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
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
 * Roborazzi golden-image tests for the app-owned [OnboardingScreen]. Runs on the
 * JVM via Robolectric and captures PNGs of the onboarding pager. Mirrors the
 * harness in feature:sync's SyncTaskEditScreenshotTest.
 *
 * The app module is flavored (foss/play); this test lives in the shared
 * `src/test` source set so it builds under both. Record / verify the foss-debug
 * variant:
 *   ./gradlew :app:recordRoborazziFossDebug
 *   ./gradlew :app:verifyRoborazziFossDebug
 *
 * Determinism: the pager defaults to page 0 (welcome) and `userScrollEnabled`
 * is false, so paging only happens via the Next button. We capture the static
 * welcome page, then advance exactly one page (welcome -> storage) — both pages
 * are plain text with no auto-advance. `waitForIdle()` settles the page-indicator
 * dot animations before each capture.
 *
 * SDK note: pinned to API 29 (not 34 like feature:sync). On API 30+ the screen
 * reads `Environment.isExternalStorageManager()` at composition, which Robolectric
 * does not shadow (throws ArrayIndexOutOfBoundsException). API 29 takes the legacy
 * `checkSelfPermission` branch, which Robolectric handles, so the screen renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreenshotTest {

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

    private val preferences: PreferencesRepository = mockk(relaxed = true) {
        every { preferences } returns flowOf(AppPreferences())
    }

    private fun viewModel() = OnboardingViewModel(preferences)

    @Test
    fun onboardingScreen_welcomePage() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OnboardingScreen(onFinished = {}, viewModel = viewModel())
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun onboardingScreen_storagePage() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OnboardingScreen(onFinished = {}, viewModel = viewModel())
                }
            }
        }
        // Advance welcome -> storage. Page 0's primary button is "Next" and only
        // advances the pager (no permission intent is launched until page 1).
        composeRule.onNodeWithText("Next").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }
}
