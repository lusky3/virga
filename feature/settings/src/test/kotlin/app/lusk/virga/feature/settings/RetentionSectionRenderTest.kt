package app.lusk.virga.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
 * Render-coverage tests for [RetentionSection]. Exercises the label lookup map so
 * that each option string resource path is compiled into coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetentionSectionRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
        options = RoborazziRule.Options(outputDirectoryPath = "src/test/snapshots"),
    )

    @Test
    fun retentionSection_forever_rendersForeverLabel() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        RetentionSection(days = 0, onDaysChange = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Keep history for").assertIsDisplayed()
        composeRule.onNodeWithText("Forever").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun retentionSection_30days_renders30DaysLabel() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        RetentionSection(days = 30, onDaysChange = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("30 days").assertIsDisplayed()
    }

    @Test
    fun retentionSection_90days_renders90DaysLabel() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        RetentionSection(days = 90, onDaysChange = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("90 days").assertIsDisplayed()
    }

    @Test
    fun retentionSection_180days_renders180DaysLabel() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        RetentionSection(days = 180, onDaysChange = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("180 days").assertIsDisplayed()
    }

    @Test
    fun retentionSection_365days_renders365DaysLabel() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        RetentionSection(days = 365, onDaysChange = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("365 days").assertIsDisplayed()
    }

    @Test
    fun retentionSection_expandDropdown_showsAllOptions() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        RetentionSection(days = 0, onDaysChange = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // Tap the dropdown to open it — exercises ExposedDropdownMenu + all DropdownMenuItems.
        composeRule.onNodeWithText("Keep history for").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("30 days").assertIsDisplayed()
        composeRule.onNodeWithText("90 days").assertIsDisplayed()
        composeRule.onNodeWithText("365 days").assertIsDisplayed()
    }
}
