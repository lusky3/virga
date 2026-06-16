package app.lusk.virga.feature.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.data.RemoteFolderPickStore
import app.lusk.virga.core.data.SyncTaskRepository
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
 * B10: Render-coverage tests for [GroupSection].
 *
 * Verifies that the group-tag and sort-order fields render with their labels
 * and that the default values from [SyncTaskForm] are displayed correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupSectionRenderTest {

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
    fun groupSection_defaultForm_showsGroupLabelField() {
        val vm = makeViewModel()
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    GroupSection(form = vm.form.value, viewModel = vm)
                }
            }
        }
        composeRule.onNodeWithText("Group label (optional)").assertIsDisplayed()
    }

    @Test
    fun groupSection_defaultForm_showsSortOrderField() {
        val vm = makeViewModel()
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    GroupSection(form = vm.form.value, viewModel = vm)
                }
            }
        }
        composeRule.onNodeWithText("Sort order").assertIsDisplayed()
    }

    @Test
    fun groupSection_withGroupTag_displaysTag() {
        val vm = makeViewModel()
        vm.update { it.copy(groupTag = "photos") }
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    GroupSection(form = vm.form.value, viewModel = vm)
                }
            }
        }
        composeRule.onNodeWithText("photos").assertIsDisplayed()
    }
}
