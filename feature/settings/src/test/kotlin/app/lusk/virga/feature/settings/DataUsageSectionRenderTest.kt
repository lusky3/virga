package app.lusk.virga.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render-coverage and interaction tests for [DataUsageSection].
 *
 * Exercises:
 *   - section title "Data usage" renders
 *   - metered-cap toggle renders in both enabled/disabled states
 *   - "used this month on metered" text renders the formatted MB value
 *   - tapping the toggle invokes the enable callback with the new value
 *   - the MB cap field renders when the cap is enabled
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DataUsageSectionRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
        options = RoborazziRule.Options(outputDirectoryPath = "src/test/snapshots"),
    )

    // --- section title / static render ---

    @Test
    fun dataUsageSection_showsSectionTitle() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataUsageSection(
                        state = AppPreferences(),
                        monthlyUsedBytes = 0L,
                        onCapEnabledChange = {},
                        onCapMbChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Data usage").assertIsDisplayed()
    }

    @Test
    fun dataUsageSection_showsCapToggleLabel() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataUsageSection(
                        state = AppPreferences(),
                        monthlyUsedBytes = 0L,
                        onCapEnabledChange = {},
                        onCapMbChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Limit monthly metered data").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage()
    }

    // --- "used this month" text ---

    @Test
    fun dataUsageSection_zeroBytes_showsZeroMb() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataUsageSection(
                        state = AppPreferences(),
                        monthlyUsedBytes = 0L,
                        onCapEnabledChange = {},
                        onCapMbChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // 0 bytes / (1024*1024) = 0 MB
        composeRule.onNodeWithText("Used this month on metered: 0 MB").assertIsDisplayed()
    }

    @Test
    fun dataUsageSection_nonZeroBytes_showsCorrectMb() {
        val fiftyMbInBytes = 50L * 1024L * 1024L
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataUsageSection(
                        state = AppPreferences(),
                        monthlyUsedBytes = fiftyMbInBytes,
                        onCapEnabledChange = {},
                        onCapMbChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Used this month on metered: 50 MB").assertIsDisplayed()
    }

    // --- toggle callback ---

    @Test
    fun dataUsageSection_tapCapToggle_invokesEnableCallback() {
        var capturedValue: Boolean? = null
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataUsageSection(
                        state = AppPreferences(meteredCapEnabled = false),
                        monthlyUsedBytes = 0L,
                        onCapEnabledChange = { capturedValue = it },
                        onCapMbChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(isToggleable()).assertIsDisplayed()
        composeRule.onNode(isToggleable()).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertThat(capturedValue).isTrue()
    }

    @Test
    fun dataUsageSection_tapCapToggle_whenEnabled_passesFalseToCallback() {
        var capturedValue: Boolean? = null
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataUsageSection(
                        state = AppPreferences(meteredCapEnabled = true, meteredCapMb = 500L),
                        monthlyUsedBytes = 0L,
                        onCapEnabledChange = { capturedValue = it },
                        onCapMbChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(isToggleable()).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertThat(capturedValue).isFalse()
    }

    // --- MB cap field visibility ---

    @Test
    fun dataUsageSection_capDisabled_doesNotShowMbField() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataUsageSection(
                        state = AppPreferences(meteredCapEnabled = false),
                        monthlyUsedBytes = 0L,
                        onCapEnabledChange = {},
                        onCapMbChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Monthly metered cap (MB)").assertDoesNotExist()
    }

    @Test
    fun dataUsageSection_capEnabled_showsMbField() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataUsageSection(
                        state = AppPreferences(meteredCapEnabled = true, meteredCapMb = 1000L),
                        monthlyUsedBytes = 0L,
                        onCapEnabledChange = {},
                        onCapMbChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Monthly metered cap (MB)").assertIsDisplayed()
    }
}
