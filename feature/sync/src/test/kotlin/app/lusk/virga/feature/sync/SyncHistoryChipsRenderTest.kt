package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SyncHistoryChipsRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        options = RoborazziRule.Options(
            outputDirectoryPath = "src/test/snapshots",
        ),
    )

    @Test
    fun searchAndStatusChips_renderedCorrectly() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        HistorySearchField(query = "test query", onQueryChange = {})
                        StatusFilterRow(statusFilter = SyncStatus.FAILED, onSelectStatus = {})
                        TaskFilterChips(
                            tasks = listOf(
                                HistoryTaskFilter(1L, "Photos"),
                                HistoryTaskFilter(2L, "Videos"),
                            ),
                            selectedTaskId = 1L,
                            onSelectTask = {},
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
