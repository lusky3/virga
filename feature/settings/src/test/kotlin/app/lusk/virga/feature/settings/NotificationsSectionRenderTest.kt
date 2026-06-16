package app.lusk.virga.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
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
 * Render-coverage and interaction tests for [NotificationsSection].
 *
 * Exercises:
 *   - section title "Notifications" renders
 *   - toggle label "Notify on failure only" renders
 *   - hint text renders
 *   - notification-settings link row renders
 *   - tapping the toggle row invokes the callback
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationsSectionRenderTest {

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

    // --- section-level render ---

    @Test
    fun notificationsSection_showsSectionTitle() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    NotificationsSection(
                        notifyOnFailureOnly = false,
                        onNotifyOnFailureOnlyChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Notifications").assertIsDisplayed()
    }

    @Test
    fun notificationsSection_showsToggleLabel() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    NotificationsSection(
                        notifyOnFailureOnly = false,
                        onNotifyOnFailureOnlyChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Notify on failure only").assertIsDisplayed()
    }

    @Test
    fun notificationsSection_showsHintText() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    NotificationsSection(
                        notifyOnFailureOnly = false,
                        onNotifyOnFailureOnlyChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Hint text may be truncated on small screens; assertIsDisplayed confirms the
        // node exists in the tree and is not hidden.
        composeRule.onNodeWithText("When on, quiet successful syncs are silenced", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun notificationsSection_showsNotificationSettingsLinkRow() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    NotificationsSection(
                        notifyOnFailureOnly = false,
                        onNotifyOnFailureOnlyChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Notification settings").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage()
    }

    // --- callback interaction ---

    @Test
    fun notificationsSection_tapToggle_invokesCallback() {
        var callbackValue: Boolean? = null
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    NotificationsSection(
                        notifyOnFailureOnly = false,
                        onNotifyOnFailureOnlyChange = { callbackValue = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // The ToggleRow registers a toggleable semantic node. Dispatch the OnClick
        // semantics action directly, bypassing pointer-input routing so Robolectric's
        // pointer-event limitations don't interfere.
        composeRule.onNode(isToggleable()).assertIsDisplayed()
        composeRule.onNode(isToggleable()).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertThat(callbackValue).isTrue()
    }
}
