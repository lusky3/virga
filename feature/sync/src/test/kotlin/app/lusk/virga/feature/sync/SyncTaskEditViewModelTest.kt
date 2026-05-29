package app.lusk.virga.feature.sync

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity
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

class SyncTaskEditViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val taskRepository: SyncTaskRepository = mockk(relaxed = true)
    private val remoteRepository: RemoteRepository = mockk {
        every { remotes } returns flowOf(
            listOf(
                RemoteEntity(name = "gdrive", type = "drive", displayName = "GDrive"),
                RemoteEntity(name = "box", type = "box", displayName = "Box"),
            ),
        )
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)

    private fun viewModel() = SyncTaskEditViewModel(taskRepository, remoteRepository, scheduler)

    @Test
    fun isValid_isFalseUntilRequiredFieldsArePopulated() {
        val vm = viewModel()
        assertThat(vm.form.value.isValid).isFalse()

        vm.update { it.copy(name = "Photos") }
        assertThat(vm.form.value.isValid).isFalse()

        vm.update { it.copy(sourcePath = "/storage/emulated/0/DCIM") }
        assertThat(vm.form.value.isValid).isFalse()

        vm.update { it.copy(remoteName = "gdrive") }
        assertThat(vm.form.value.isValid).isTrue()
    }

    @Test
    fun save_doesNothingWhenFormInvalid() = runTest(mainDispatcher.dispatcher) {
        var calledBack = false
        viewModel().save { calledBack = true }
        advanceUntilIdle()

        assertThat(calledBack).isFalse()
        coVerify(exactly = 0) { taskRepository.save(any()) }
        coVerify(exactly = 0) { scheduler.schedule(any()) }
    }

    @Test
    fun save_persistsTrimmedFieldsAndReschedules() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 99L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "  Photos  ",
                sourcePath = " /storage/emulated/0/DCIM ",
                remoteName = "gdrive",
                remotePath = " /Photos ",
                direction = SyncDirection.BISYNC,
                intervalMinutes = 60,
                wifiOnly = false,
            )
        }

        var onSavedCalled = false
        vm.save { onSavedCalled = true }
        advanceUntilIdle()

        assertThat(onSavedCalled).isTrue()
        coVerifyOrder {
            taskRepository.save(
                match<SyncTaskEntity> {
                    it.name == "Photos" &&
                        it.sourcePath == "/storage/emulated/0/DCIM" &&
                        it.remoteName == "gdrive" &&
                        it.remotePath == "/Photos" &&
                        it.direction == SyncDirection.BISYNC &&
                        it.intervalMinutes == 60 &&
                        !it.wifiOnly
                },
            )
            scheduler.schedule(match<SyncTaskEntity> { it.id == 99L })
        }
    }

    @Test
    fun load_negativeIdLeavesBlankForm() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.load(taskId = -1)
        advanceUntilIdle()

        assertThat(vm.form.value).isEqualTo(SyncTaskForm())
        coVerify(exactly = 0) { taskRepository.getTask(any()) }
    }

    @Test
    fun load_existingTaskHydratesForm() = runTest(mainDispatcher.dispatcher) {
        val entity = SyncTaskEntity(
            id = 7,
            name = "Music",
            sourcePath = "/storage/emulated/0/Music",
            remoteName = "box",
            remotePath = "/Music",
            direction = SyncDirection.DOWNLOAD,
            intervalMinutes = 1440,
            wifiOnly = true,
        )
        coEvery { taskRepository.getTask(7) } returns entity

        val vm = viewModel()
        vm.load(taskId = 7)
        advanceUntilIdle()

        assertThat(vm.form.value).isEqualTo(
            SyncTaskForm(
                id = 7,
                name = "Music",
                sourcePath = "/storage/emulated/0/Music",
                remoteName = "box",
                remotePath = "/Music",
                direction = SyncDirection.DOWNLOAD,
                intervalMinutes = 1440,
                wifiOnly = true,
            ),
        )
    }
}
