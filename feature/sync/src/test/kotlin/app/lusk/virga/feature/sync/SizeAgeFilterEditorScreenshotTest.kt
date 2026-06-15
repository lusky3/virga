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
 * Roborazzi golden screenshots of [SizeAgeFilterEditor] (B5).
 * Covers the blank/unset state and the state where values and error messages are shown.
 *
 * Regenerate: ./gradlew :feature:sync:recordRoborazziDebug
 * Verify (CI): ./gradlew :feature:sync:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SizeAgeFilterEditorScreenshotTest {

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
    fun sizeAgeFilterEditor_blank() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SizeAgeFilterEditor(
                        state = SizeAgeFilterState(),
                        onChange = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun sizeAgeFilterEditor_withValuesAndErrors() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    SizeAgeFilterEditor(
                        state = SizeAgeFilterState(
                            minSize = "10M",
                            maxSize = "badvalue!!",
                            minAge = "30d",
                            maxAge = "notanage",
                            maxSizeError = "Invalid size — use e.g. 10M, 1.5G, or 512",
                            maxAgeError = "Invalid age — use e.g. 30d, 1h30m, or 100ms",
                        ),
                        onChange = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
