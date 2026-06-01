package app.lusk.virga.feature.sync

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.PendingRemoteResult
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

/**
 * Robolectric-backed Compose test — runs on the JVM, no emulator. Verifies that
 * the Save button gating in [SyncTaskEditScreen] actually reflects
 * [SyncTaskForm.isValid], i.e. the UI honours the validation rule the
 * ViewModel exposes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTaskEditScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val taskRepository: SyncTaskRepository = mockk(relaxed = true)
    private val remoteRepository: RemoteRepository = mockk {
        every { remotes } returns flowOf(
            listOf(Remote(name = "gdrive", type = "drive")),
        )
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)

    private fun setContent(): SyncTaskEditViewModel {
        val viewModel = SyncTaskEditViewModel(taskRepository, remoteRepository, scheduler, RemoteFolderPickStore(), PendingRemoteResult())
        composeRule.setContent {
            SyncTaskEditScreen(taskId = 0L, onBack = {}, viewModel = viewModel)
        }
        return viewModel
    }

    @Test
    fun saveButton_isDisabled_whenFormIsEmpty() {
        setContent()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun saveButton_remainsDisabled_withOnlyNameFilled() {
        setContent()
        composeRule.onNodeWithText("Task name *").performTextInput("Photos")
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun saveButton_enables_onceAllRequiredFieldsFilled() {
        val viewModel = setContent()

        composeRule.onNodeWithText("Task name *").performTextInput("Photos")
        composeRule.onNodeWithText("Local path *")
            .performTextInput("/storage/emulated/0/DCIM")
        // RemoteDropdown is a read-only OutlinedTextField; setting the remote
        // through the ViewModel is equivalent and avoids dealing with the
        // ExposedDropdownMenu under Robolectric's view hierarchy.
        viewModel.update { it.copy(remoteName = "gdrive", remotePath = "Backups") }

        composeRule.onNodeWithText("Save").assertIsEnabled()
    }
}
