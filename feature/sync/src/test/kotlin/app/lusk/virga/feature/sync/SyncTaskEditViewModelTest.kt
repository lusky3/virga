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

    // --- pre-existing tests -------------------------------------------------

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

    // --- load(new) defaults -------------------------------------------------

    @Test
    fun load_newTask_startsWithBlankFormDefaults() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.load(taskId = 0)
        advanceUntilIdle()

        val form = vm.form.value
        assertThat(form.id).isEqualTo(0L)
        assertThat(form.name).isEmpty()
        assertThat(form.sourcePath).isEmpty()
        assertThat(form.remoteName).isEmpty()
        assertThat(form.direction).isEqualTo(SyncDirection.UPLOAD)
        assertThat(form.wifiOnly).isTrue()
        assertThat(form.bufferSize).isEqualTo("16M")
    }

    // --- non-preset interval → CUSTOM mapping -------------------------------

    @Test
    fun load_nonPresetInterval_mapsToCustomSentinelAndStoresActualInCustomIntervalMinutes() = runTest(mainDispatcher.dispatcher) {
        val entity = task(id = 3, intervalMinutes = 45)
        coEvery { taskRepository.getTask(3L) } returns entity

        val vm = viewModel()
        vm.load(taskId = 3L)
        advanceUntilIdle()

        // sentinel value that drives the "Custom" radio selection
        assertThat(vm.form.value.intervalMinutes).isEqualTo(-1)
        assertThat(vm.form.value.customIntervalMinutes).isEqualTo(45)
    }

    @Test
    fun load_presetInterval_doesNotMapToCustomSentinel() = runTest(mainDispatcher.dispatcher) {
        val entity = task(id = 4, intervalMinutes = 60)
        coEvery { taskRepository.getTask(4L) } returns entity

        val vm = viewModel()
        vm.load(taskId = 4L)
        advanceUntilIdle()

        assertThat(vm.form.value.intervalMinutes).isEqualTo(60)
        assertThat(vm.form.value.customIntervalMinutes).isNull()
    }

    // --- prefillRemote / prefillRemotePath ----------------------------------

    @Test
    fun load_newTask_appliesPrefillRemote() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.load(taskId = 0, prefillRemote = "gdrive", prefillRemotePath = "/Photos")
        advanceUntilIdle()

        assertThat(vm.form.value.remoteName).isEqualTo("gdrive")
        assertThat(vm.form.value.remotePath).isEqualTo("/Photos")
    }

    @Test
    fun load_existingTask_ignoresPrefillRemote() = runTest(mainDispatcher.dispatcher) {
        val entity = task(id = 5, remoteName = "dropbox")
        coEvery { taskRepository.getTask(5L) } returns entity

        val vm = viewModel()
        vm.load(taskId = 5L, prefillRemote = "gdrive", prefillRemotePath = "/shouldBeIgnored")
        advanceUntilIdle()

        assertThat(vm.form.value.remoteName).isEqualTo("dropbox")
    }

    // --- validation — errors only after touched / submitAttempted -----------

    @Test
    fun nameError_isNullBeforeTouchedOrSubmitAttempted() {
        val vm = viewModel()
        vm.update { it.copy(name = "") }

        assertThat(vm.form.value.nameError).isNull()
    }

    @Test
    fun nameError_isSetAfterTouched() {
        val vm = viewModel()
        vm.update { it.copy(name = "") }
        vm.touchName()

        assertThat(vm.form.value.nameError).isEqualTo("Name is required")
    }

    @Test
    fun nameError_isSetAfterSubmitAttempted() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.save {}
        advanceUntilIdle()

        assertThat(vm.form.value.nameError).isEqualTo("Name is required")
    }

    @Test
    fun sourcePathError_isNullBeforeTouchedOrSubmitAttempted() {
        val vm = viewModel()
        vm.update { it.copy(sourcePath = "") }

        assertThat(vm.form.value.sourcePathError).isNull()
    }

    @Test
    fun sourcePathError_isSetAfterTouched() {
        val vm = viewModel()
        vm.update { it.copy(sourcePath = "") }
        vm.touchSourcePath()

        assertThat(vm.form.value.sourcePathError).isEqualTo("Source path is required")
    }

    @Test
    fun remoteNameError_isNullBeforeTouchedOrSubmitAttempted() {
        val vm = viewModel()
        vm.update { it.copy(remoteName = "") }

        assertThat(vm.form.value.remoteNameError).isNull()
    }

    @Test
    fun remoteNameError_isSetAfterTouched() {
        val vm = viewModel()
        vm.update { it.copy(remoteName = "") }
        vm.touchRemoteName()

        assertThat(vm.form.value.remoteNameError).isNotNull()
    }

    // --- bwLimit validation — wifi and metered are independent --------------

    @Test
    fun bwLimitWifiError_isSetForInvalidFormat_bwLimitMeteredError_isNull() {
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "bad!!!", bwLimitMetered = "1M") }

        assertThat(vm.form.value.bwLimitWifiError).isNotNull()
        assertThat(vm.form.value.bwLimitMeteredError).isNull()
    }

    @Test
    fun bwLimitMeteredError_isSetForInvalidFormat_bwLimitWifiError_isNull() {
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "2M", bwLimitMetered = "xyz???") }

        assertThat(vm.form.value.bwLimitWifiError).isNull()
        assertThat(vm.form.value.bwLimitMeteredError).isNotNull()
    }

    @Test
    fun bwLimitWifiError_isNullForBlankValue() {
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "") }

        assertThat(vm.form.value.bwLimitWifiError).isNull()
    }

    @Test
    fun bwLimitWifiError_isNullForValidRateWithColonTimespec() {
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "10M:1M") }

        assertThat(vm.form.value.bwLimitWifiError).isNull()
    }

    // --- customIntervalError blocks isValid when custom < 15 ----------------

    @Test
    fun customIntervalError_isSetWhenCustomIntervalBelowMinimum() {
        val vm = viewModel()
        vm.update { it.copy(intervalMinutes = -1, customIntervalMinutes = 10) }

        assertThat(vm.form.value.customIntervalError).isEqualTo("Minimum 15 minutes")
    }

    @Test
    fun isValid_isFalseWhenCustomIntervalTooShort() {
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "T",
                sourcePath = "/s",
                remoteName = "r",
                intervalMinutes = -1,
                customIntervalMinutes = 10,
            )
        }

        assertThat(vm.form.value.isValid).isFalse()
    }

    @Test
    fun customIntervalError_isNullWhenCustomIntervalAtMinimum() {
        val vm = viewModel()
        vm.update { it.copy(intervalMinutes = -1, customIntervalMinutes = 15) }

        assertThat(vm.form.value.customIntervalError).isNull()
    }

    // --- applySourcePath ----------------------------------------------------

    @Test
    fun applySourcePath_setsPathAndMarksTouched() {
        val vm = viewModel()
        vm.applySourcePath("/sdcard/DCIM")

        assertThat(vm.form.value.sourcePath).isEqualTo("/sdcard/DCIM")
        assertThat(vm.form.value.sourcePathTouched).isTrue()
    }

    // --- save with custom interval ------------------------------------------

    @Test
    fun save_customInterval_writesCustomIntervalMinutesToEntity() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "Backup",
                sourcePath = "/sdcard",
                remoteName = "gdrive",
                intervalMinutes = -1,
                customIntervalMinutes = 20,
            )
        }

        vm.save {}
        advanceUntilIdle()

        coVerify {
            taskRepository.save(match<SyncTaskEntity> { it.intervalMinutes == 20 })
        }
    }

    @Test
    fun save_presetInterval_writesPresetIntervalToEntity() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "Backup",
                sourcePath = "/sdcard",
                remoteName = "gdrive",
                intervalMinutes = 360,
            )
        }

        vm.save {}
        advanceUntilIdle()

        coVerify {
            taskRepository.save(match<SyncTaskEntity> { it.intervalMinutes == 360 })
        }
    }

    @Test
    fun save_callsOnSavedAndSchedules() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 55L
        val vm = viewModel()
        vm.update { it.copy(name = "T", sourcePath = "/s", remoteName = "r") }
        var saved = false

        vm.save { saved = true }
        advanceUntilIdle()

        assertThat(saved).isTrue()
        coVerify(exactly = 1) { scheduler.schedule(match<SyncTaskEntity> { it.id == 55L }) }
    }

    // --- helpers ------------------------------------------------------------

    private fun task(
        id: Long,
        remoteName: String = "gdrive",
        intervalMinutes: Int? = null,
    ) = SyncTaskEntity(
        id = id,
        name = "task-$id",
        sourcePath = "/src",
        remoteName = remoteName,
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = intervalMinutes,
    )
}
