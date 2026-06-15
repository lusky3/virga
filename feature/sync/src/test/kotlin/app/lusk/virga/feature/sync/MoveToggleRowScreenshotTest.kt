package app.lusk.virga.feature.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
 * Roborazzi golden screenshots of [MoveToggleRow] (0.3.0): enabled state and
 * inert (disabled) state. Matches the pattern used by other screenshot tests in
 * this module. Goldens live in src/test/snapshots/.
 *
 * Regenerate: ./gradlew :feature:sync:recordRoborazziDebug
 * Verify (CI): ./gradlew :feature:sync:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoveToggleRowScreenshotTest {

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
    fun moveToggleRow_enabled() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    MoveToggleRow(enabled = true, onChange = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun moveToggleRow_inert() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    MoveToggleRow(enabled = false, onChange = {}, inert = true)
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
