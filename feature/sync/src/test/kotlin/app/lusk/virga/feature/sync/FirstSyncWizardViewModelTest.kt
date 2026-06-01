package app.lusk.virga.feature.sync

import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.sync.SyncScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class FirstSyncWizardViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val taskRepository: SyncTaskRepository = mockk(relaxed = true)
    private val remoteRepository: RemoteRepository = mockk {
        every { remotes } returns flowOf(listOf(Remote(name = "gdrive", type = "drive")))
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)

    private fun viewModel() = FirstSyncWizardViewModel(
        taskRepository,
        remoteRepository,
        scheduler,
        PendingRemoteResult(),
    )

    @Test
    fun initialStep_isIntro() {
        assertThat(viewModel().state.value.step).isEqualTo(WizardStep.INTRO)
    }

    @Test
    fun goNext_fromIntro_advancesToAccount() {
        val vm = viewModel()
        vm.goNext()
        assertThat(vm.state.value.step).isEqualTo(WizardStep.ACCOUNT)
    }

    @Test
    fun goNext_fromAccount_withoutRemote_doesNotAdvance() {
        val vm = viewModel()
        vm.goNext() // INTRO → ACCOUNT
        vm.goNext() // should stay on ACCOUNT (no remote selected)
        assertThat(vm.state.value.step).isEqualTo(WizardStep.ACCOUNT)
    }

    @Test
    fun goNext_fromAccount_withRemote_advancesToSource() {
        val vm = viewModel()
        vm.goNext() // → ACCOUNT
        vm.selectRemote("gdrive")
        vm.goNext() // → SOURCE
        assertThat(vm.state.value.step).isEqualTo(WizardStep.SOURCE)
    }

    @Test
    fun goNext_fromSource_withPath_advancesToDestination() {
        val vm = viewModel()
        vm.goNext(); vm.selectRemote("gdrive"); vm.goNext() // → SOURCE
        vm.applySourcePath("/sdcard/DCIM")
        vm.goNext()
        assertThat(vm.state.value.step).isEqualTo(WizardStep.DESTINATION)
    }

    @Test
    fun applySourcePath_defaultsTaskNameToFolderName() {
        val vm = viewModel()
        vm.applySourcePath("/sdcard/Photos")
        assertThat(vm.state.value.taskName).isEqualTo("Photos")
    }

    @Test
    fun applySourcePath_doesNotOverrideUserEnteredName() {
        val vm = viewModel()
        vm.setTaskName("My Backup")
        vm.applySourcePath("/sdcard/Photos")
        assertThat(vm.state.value.taskName).isEqualTo("My Backup")
    }

    @Test
    fun goBack_fromAccount_returnsToIntro() {
        val vm = viewModel()
        vm.goNext() // → ACCOUNT
        vm.goBack()
        assertThat(vm.state.value.step).isEqualTo(WizardStep.INTRO)
    }

    @Test
    fun goBack_fromIntro_staysOnIntro() {
        val vm = viewModel()
        vm.goBack()
        assertThat(vm.state.value.step).isEqualTo(WizardStep.INTRO)
    }

    @Test
    fun save_persistsTaskAndSchedules() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 42L
        val vm = viewModel()
        vm.selectRemote("gdrive")
        vm.applySourcePath("/sdcard/DCIM")
        vm.setRemotePath("Backups/DCIM")
        vm.setDirection(SyncDirection.UPLOAD)
        // applySourcePath already set name to "DCIM"

        var savedId = -1L
        vm.save { id -> savedId = id }
        advanceUntilIdle()

        assertThat(savedId).isEqualTo(42L)
        coVerifyOrder {
            taskRepository.save(
                match<SyncTask> {
                    it.name == "DCIM" &&
                        it.remoteName == "gdrive" &&
                        it.sourcePath == "/sdcard/DCIM" &&
                        it.remotePath == "Backups/DCIM" &&
                        it.direction == SyncDirection.UPLOAD
                },
            )
            scheduler.schedule(match<SyncTask> { it.id == 42L })
        }
    }

    @Test
    fun save_doesNothingWhenTaskNameIsBlank() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.selectRemote("gdrive")
        vm.applySourcePath("/sdcard/DCIM")
        vm.setRemotePath("Backups/DCIM")
        vm.setTaskName("") // blank out the auto-set name

        var callbackFired = false
        vm.save { callbackFired = true }
        advanceUntilIdle()

        assertThat(callbackFired).isFalse()
        coVerify(exactly = 0) { taskRepository.save(any()) }
    }

    @Test
    fun pendingRemoteResult_autoSelectsNewRemote() = runTest(mainDispatcher.dispatcher) {
        val pendingResult = PendingRemoteResult()
        val vm = FirstSyncWizardViewModel(taskRepository, remoteRepository, scheduler, pendingResult)

        pendingResult.created("dropbox")
        advanceUntilIdle()

        assertThat(vm.state.value.remoteName).isEqualTo("dropbox")
    }

    @Test
    fun pendingRemoteResult_doesNotClobberAlreadySelectedRemote() = runTest(mainDispatcher.dispatcher) {
        val pendingResult = PendingRemoteResult()
        val vm = FirstSyncWizardViewModel(taskRepository, remoteRepository, scheduler, pendingResult)
        vm.selectRemote("gdrive")

        pendingResult.created("dropbox")
        advanceUntilIdle()

        // Pre-selected remote must not be overwritten
        assertThat(vm.state.value.remoteName).isEqualTo("gdrive")
    }

    @Test
    fun canFinish_isFalseUntilRequiredFieldsPresent() {
        val vm = viewModel()
        assertThat(vm.state.value.canFinish).isFalse()

        vm.selectRemote("gdrive")
        vm.applySourcePath("/sdcard/DCIM")
        vm.setRemotePath("Backups")
        // applySourcePath set taskName = "DCIM", so all fields are present
        assertThat(vm.state.value.canFinish).isTrue()
    }
}
