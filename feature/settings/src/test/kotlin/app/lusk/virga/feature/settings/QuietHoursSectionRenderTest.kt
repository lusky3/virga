package app.lusk.virga.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
 * Render-coverage tests for [QuietHoursSection] and [formatMinutesOfDay].
 *
 * Exercises:
 *   - disabled state: toggle row + hint text render, pickers hidden
 *   - enabled state: QuietHoursTimePickers / QuietHoursTimeColumn render (start/end buttons)
 *   - tapping a time button: MinuteTimePickerDialog appears
 *   - formatMinutesOfDay: called via text rendered in enabled state
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuietHoursSectionRenderTest {

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

    // --- disabled state ------------------------------------------------------

    @Test
    fun quietHoursSection_disabled_showsToggleAndHint() {
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QuietHoursSection(
                        state = QuietHoursUiState(
                            enabled = false,
                            startMinutes = 22 * 60,
                            endMinutes = 7 * 60,
                        ),
                        onEnabledChange = {},
                        onStartChange = {},
                        onEndChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Enable quiet hours").assertIsDisplayed()
        composeRule.onNodeWithText("Quiet hours").assertIsDisplayed()
    }

    @Test
    fun quietHoursSection_disabled_hidesTimePickers() {
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QuietHoursSection(
                        state = QuietHoursUiState(
                            enabled = false,
                            startMinutes = 22 * 60,
                            endMinutes = 7 * 60,
                        ),
                        onEnabledChange = {},
                        onStartChange = {},
                        onEndChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Start/End time columns should not be visible when disabled.
        composeRule.onNodeWithText("Start time").assertDoesNotExist()
        composeRule.onNodeWithText("End time").assertDoesNotExist()
    }

    // --- enabled state -------------------------------------------------------

    @Test
    fun quietHoursSection_enabled_showsStartAndEndLabels() {
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QuietHoursSection(
                        state = QuietHoursUiState(
                            enabled = true,
                            startMinutes = 22 * 60,
                            endMinutes = 7 * 60,
                        ),
                        onEnabledChange = {},
                        onStartChange = {},
                        onEndChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Start time").assertIsDisplayed()
        composeRule.onNodeWithText("End time").assertIsDisplayed()
    }

    @Test
    fun quietHoursSection_enabled_showsFormattedTimes() {
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QuietHoursSection(
                        state = QuietHoursUiState(
                            enabled = true,
                            startMinutes = 22 * 60,       // "22:00"
                            endMinutes = 7 * 60 + 30,     // "07:30"
                        ),
                        onEnabledChange = {},
                        onStartChange = {},
                        onEndChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // formatMinutesOfDay is exercised by the rendered text buttons.
        composeRule.onNodeWithText("22:00").assertIsDisplayed()
        composeRule.onNodeWithText("07:30").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage()
    }

    // --- dialog open ---------------------------------------------------------

    @Test
    fun quietHoursSection_enabled_tapStartButton_opensPickerDialog() {
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QuietHoursSection(
                        state = QuietHoursUiState(
                            enabled = true,
                            startMinutes = 22 * 60,
                            endMinutes = 7 * 60,
                        ),
                        onEnabledChange = {},
                        onStartChange = {},
                        onEndChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("22:00").performClick()
        composeRule.waitForIdle()
        // Dialog confirm button proves the picker dialog opened.
        composeRule.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    fun quietHoursSection_enabled_tapEndButton_opensPickerDialog() {
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    QuietHoursSection(
                        state = QuietHoursUiState(
                            enabled = true,
                            startMinutes = 22 * 60,
                            endMinutes = 7 * 60,
                        ),
                        onEnabledChange = {},
                        onStartChange = {},
                        onEndChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("07:00").performClick()
        composeRule.waitForIdle()
        // Dialog dismiss button proves the picker dialog opened.
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    // --- formatMinutesOfDay unit coverage ------------------------------------

    @Test
    fun formatMinutesOfDay_midnight_returns_00_00() {
        assert(formatMinutesOfDay(0) == "00:00")
    }

    @Test
    fun formatMinutesOfDay_midday_returns_12_00() {
        assert(formatMinutesOfDay(12 * 60) == "12:00")
    }

    @Test
    fun formatMinutesOfDay_maxValue_returns_23_59() {
        assert(formatMinutesOfDay(23 * 60 + 59) == "23:59")
    }

    @Test
    fun formatMinutesOfDay_clampsNegative() {
        assert(formatMinutesOfDay(-1) == "00:00")
    }

    @Test
    fun formatMinutesOfDay_clampsAboveMax() {
        assert(formatMinutesOfDay(1440) == "23:59")
    }
}
