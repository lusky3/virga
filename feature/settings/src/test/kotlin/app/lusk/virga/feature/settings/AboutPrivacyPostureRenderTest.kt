package app.lusk.virga.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render tests for the privacy-posture block inside [AboutScreen].
 *
 * Scrolls to nodes before asserting display — the privacy section lives below the fold
 * in the fixed-size Robolectric viewport. Uses [AboutScreen] directly with no-op lambdas
 * and stub values so the private composable need not be exposed.
 *
 * Generate / refresh: ./gradlew :feature:settings:recordRoborazziDebug
 * Verify (CI default):  ./gradlew :feature:settings:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h800dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutPrivacyPostureRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
        options = RoborazziRule.Options(outputDirectoryPath = "src/test/snapshots"),
    )

    private fun setAboutScreen() {
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AboutScreen(
                        onBack = {},
                        onViewChangelog = {},
                        distribution = "foss",
                        rcloneVersion = "v1.74.2",
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun aboutScreen_privacySection_showsSectionTitle() {
        setAboutScreen()
        composeRule.onNodeWithText("Privacy")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aboutScreen_privacySection_showsIntroLine() {
        setAboutScreen()
        composeRule
            .onNodeWithText("Virga is open source and runs entirely on your device.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aboutScreen_privacySection_showsNoAnalyticsLine() {
        setAboutScreen()
        composeRule
            .onNodeWithText("— No analytics or telemetry.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aboutScreen_privacySection_showsLocalDataLine() {
        setAboutScreen()
        composeRule
            .onNodeWithText(
                "— Your credentials and files never leave your device except to the" +
                    " cloud services you configure.",
            )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onRoot().captureRoboImage()
    }
}
