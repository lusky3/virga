package app.lusk.virga.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
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
 * Render-coverage and callback tests for [EventTriggersSection].
 *
 * Exercises:
 *  - section title renders
 *  - hint text renders
 *  - each toggle label renders
 *  - tapping each toggle invokes its callback
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h800dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventTriggersSectionRenderTest {

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

    private fun setContent(
        folderChange: Boolean = false,
        wifiConnect: Boolean = false,
        charge: Boolean = false,
        onFolder: (Boolean) -> Unit = {},
        onWifi: (Boolean) -> Unit = {},
        onCharge: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    EventTriggersSection(
                        triggerOnFolderChange = folderChange,
                        triggerOnWifiConnect = wifiConnect,
                        triggerOnCharge = charge,
                        onFolderChangeToggle = onFolder,
                        onWifiConnectToggle = onWifi,
                        onChargeToggle = onCharge,
                    )
                }
            }
        }
    }

    // --- section-level render ---

    @Test
    fun eventTriggersSection_showsSectionTitle() {
        setContent()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Event triggers").assertIsDisplayed()
    }

    @Test
    fun eventTriggersSection_showsHintText() {
        setContent()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Requires the watchdog", substring = true).assertIsDisplayed()
    }

    @Test
    fun eventTriggersSection_showsFolderToggleLabel() {
        setContent()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sync on folder change").assertIsDisplayed()
    }

    @Test
    fun eventTriggersSection_showsWifiToggleLabel() {
        setContent()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sync when Wi-Fi connects").assertIsDisplayed()
    }

    @Test
    fun eventTriggersSection_showsChargeToggleLabel() {
        setContent()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sync when charging starts").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage()
    }

    // --- callback interaction ---

    @Test
    fun eventTriggersSection_tapFirstToggle_invokesFolderCallback() {
        var result: Boolean? = null
        setContent(onFolder = { result = it })
        composeRule.waitForIdle()
        composeRule.onAllNodes(isToggleable()).onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertThat(result).isTrue()
    }
}
