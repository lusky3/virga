package app.lusk.virga.feature.sync

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import app.lusk.virga.core.common.model.NamedSyncRun
import app.lusk.virga.core.common.model.SyncRun
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import app.lusk.virga.sync.SyncScheduler
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render tests for [SyncHistoryScreen]. Exercises the full screen composable under
 * Robolectric so that LazyColumn + RunCard + overflow menu paths contribute to coverage,
 * not just the section-level composables tested by [SyncHistoryChipsRenderTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SyncHistoryScreenRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        options = RoborazziRule.Options(
            outputDirectoryPath = "src/test/snapshots",
        ),
    )

    private val run1 = SyncRun(
        id = 1L, taskId = 10L, status = SyncStatus.SUCCESS,
        startedAtEpochMs = 0L, endedAtEpochMs = 60_000L,
        filesTransferred = 5, bytesTransferred = 512_000L,
    )
    private val run2 = SyncRun(
        id = 2L, taskId = 10L, status = SyncStatus.FAILED,
        startedAtEpochMs = 120_000L,
        filesTransferred = 0, bytesTransferred = 0L,
        errorCount = 1, errorMessage = "network error",
    )

    private fun viewModel(pagingData: PagingData<NamedSyncRun> = PagingData.empty()): SyncHistoryViewModel {
        val historyRepository: SyncHistoryRepository = mockk(relaxed = true) {
            every { distinctTaskIds } returns MutableStateFlow(emptyList<Long>())
            every { recentRuns } returns flowOf(emptyList())
            every { pagedRuns(any(), any(), any()) } returns flowOf(pagingData)
        }
        val taskRepository: SyncTaskRepository = mockk(relaxed = true) {
            every { tasks } returns flowOf(emptyList())
        }
        val scheduler: SyncScheduler = mockk(relaxed = true)
        return SyncHistoryViewModel(historyRepository, taskRepository, scheduler)
    }

    @Test
    fun syncHistoryScreen_emptyState_renders() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    SyncHistoryScreen(
                        onBack = {},
                        onOpenRun = {},
                        viewModel = viewModel(PagingData.empty()),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun syncHistoryScreen_withRuns_rendersRunCards() {
        val items = listOf(
            NamedSyncRun(run1, "Photos"),
            NamedSyncRun(run2, null),
        )
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    SyncHistoryScreen(
                        onBack = {},
                        onOpenRun = {},
                        viewModel = viewModel(PagingData.from(items)),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun syncHistoryScreen_overflowMenu_opensAndShowsItems() {
        val items = listOf(NamedSyncRun(run1, "Photos"))
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    SyncHistoryScreen(
                        onBack = {},
                        onOpenRun = {},
                        viewModel = viewModel(PagingData.from(items)),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Open the overflow menu so DropdownMenu + all three DropdownMenuItems compose.
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }
}
