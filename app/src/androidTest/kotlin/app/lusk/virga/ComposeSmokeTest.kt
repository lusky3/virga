package app.lusk.virga

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lusk.virga.ui.theme.VirgaTheme
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented smoke test. Verifies the Compose pipeline is wired up on the
 * test device without involving Hilt — useful as a fast first signal that
 * the AndroidJUnit setup works before Hilt-graph tests run.
 *
 * Run with `./gradlew :app:connectedFossDebugAndroidTest` against an emulator
 * or device with `adb` reachable.
 */
class ComposeSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun virgaTheme_rendersChildContent() {
        composeRule.setContent {
            VirgaTheme {
                Text("Virga smoke test")
            }
        }

        composeRule.onNodeWithText("Virga smoke test").assertIsDisplayed()
    }
}
