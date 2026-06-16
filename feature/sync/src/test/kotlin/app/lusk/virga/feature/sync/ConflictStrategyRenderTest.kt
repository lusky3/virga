package app.lusk.virga.feature.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteFolderPickStore
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.sync.SyncScheduler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B7 render-coverage tests for [ConflictStrategySection] and [ConflictTypeBadge].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConflictStrategyRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val taskRepository: SyncTaskRepository = mockk(relaxed = true)
    private val remoteRepository: RemoteRepository = mockk {
        every { remotes } returns flowOf(listOf(Remote(name = "gdrive", type = "drive")))
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)
    private val prefsRepository: PreferencesRepository = mockk(relaxed = true) {
        every { preferences } returns flowOf(AppPreferences())
    }

    private fun makeViewModel() = SyncTaskEditViewModel(
        taskRepository, remoteRepository, scheduler,
        RemoteFolderPickStore(), PendingRemoteResult(), prefsRepository,
    )

    @Test
    fun conflictStrategySection_bisync_showsConflictResolveDropdown() {
        val vm = makeViewModel()
        vm.update { it.copy(direction = SyncDirection.BISYNC) }
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ConflictStrategySection(form = vm.form.value, viewModel = vm)
                }
            }
        }
        composeRule.onNodeWithText("Conflict resolve").assertIsDisplayed()
    }

    @Test
    fun conflictStrategySection_upload_showsConflictCheckToggle() {
        val vm = makeViewModel()
        vm.update { it.copy(direction = SyncDirection.UPLOAD) }
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ConflictStrategySection(form = vm.form.value, viewModel = vm)
                }
            }
        }
        composeRule.onNodeWithText("Pre-sync conflict check").assertIsDisplayed()
    }

    @Test
    fun conflictTypeBadge_bisync_rendersText() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ConflictTypeBadge("bisync")
                }
            }
        }
        composeRule.onNodeWithText("bisync").assertIsDisplayed()
    }

    @Test
    fun conflictTypeBadge_oneWay_rendersText() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ConflictTypeBadge("one-way")
                }
            }
        }
        composeRule.onNodeWithText("one-way").assertIsDisplayed()
    }
}
