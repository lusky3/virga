package app.lusk.virga.lock

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi golden-image test for [LockScreen]. Mirrors the pattern used by
 * [app.lusk.virga.onboarding.OnboardingScreenshotTest].
 *
 * Record / refresh the golden:
 *   ./gradlew :app:recordRoborazziFossDebug
 * Verify against the golden (CI default):
 *   ./gradlew :app:verifyRoborazziFossDebug
 *
 * The [onUnlock] callback is wired to a no-op lambda — the auto-prompt
 * [LaunchedEffect] fires it on composition, but there is no biometric hardware
 * under Robolectric, so nothing crashes and the static lock UI is captured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockScreenScreenshotTest {

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

    @Test
    fun lockScreen_default() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LockScreen(onUnlock = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }
}
