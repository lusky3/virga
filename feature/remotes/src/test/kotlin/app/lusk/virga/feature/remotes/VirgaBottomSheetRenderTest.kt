package app.lusk.virga.feature.remotes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.lusk.virga.core.designsystem.back.LocalOverlayBackRegistry
import app.lusk.virga.core.designsystem.back.OverlayBackRegistry
import app.lusk.virga.core.designsystem.component.VirgaBottomSheet
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render coverage for [VirgaBottomSheet] — the in-window modal sheet that replaced
 * Material3's separate-dialog-window `ModalBottomSheet` so Back reaches the nav host.
 * Verifies the content + scrim render and that the sheet claims Back by registering
 * with the host [OverlayBackRegistry] via `DismissOnBack`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VirgaBottomSheetRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sheet_rendersContentAndScrim_andRegistersBackDismisser() {
        val registry = OverlayBackRegistry()
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    CompositionLocalProvider(LocalOverlayBackRegistry provides registry) {
                        VirgaBottomSheet(onDismiss = {}, scrimDescription = "Dismiss sheet") {
                            Text("Sheet body content")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Sheet body content").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss sheet").assertIsDisplayed()
        // DismissOnBack registered with the host, so Back will close this sheet.
        assertThat(registry.hasOverlay).isTrue()
    }

    @Test
    fun sheet_withoutRegistry_stillRendersContent() {
        // No LocalOverlayBackRegistry provided (e.g. isolated preview) — DismissOnBack
        // is a no-op and the sheet must still render.
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    VirgaBottomSheet(onDismiss = {}, scrimDescription = "Dismiss") {
                        Text("Body")
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Body").assertIsDisplayed()
    }
}
