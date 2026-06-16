package app.lusk.virga.feature.sync

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.data.RemoteFolderPickStore
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.SyncTask
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
                Remote(name = "gdrive", type = "drive"),
                Remote(name = "box", type = "box"),
            ),
        )
    }
    private val scheduler: SyncScheduler = mockk(relaxed = true)

    private fun viewModel() = SyncTaskEditViewModel(taskRepository, remoteRepository, scheduler, RemoteFolderPickStore(), PendingRemoteResult(), prefsRepo())

    private fun viewModel(prefs: AppPreferences) = SyncTaskEditViewModel(taskRepository, remoteRepository, scheduler, RemoteFolderPickStore(), PendingRemoteResult(), prefsRepo(prefs))

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
        assertThat(vm.form.value.isValid).isFalse() // destination still required

        vm.update { it.copy(remotePath = "Backups/DCIM") }
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
                match<SyncTask> {
                    it.name == "Photos" &&
                        it.sourcePath == "/storage/emulated/0/DCIM" &&
                        it.remoteName == "gdrive" &&
                        it.remotePath == "/Photos" &&
                        it.direction == SyncDirection.BISYNC &&
                        it.intervalMinutes == 60 &&
                        !it.wifiOnly
                },
            )
            scheduler.schedule(match<SyncTask> { it.id == 99L })
        }
    }

    @Test
    fun save_persistsRequiresCharging() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "Photos",
                sourcePath = "/storage/emulated/0/DCIM",
                remoteName = "gdrive",
                remotePath = "/Photos",
                requiresCharging = true,
            )
        }
        vm.save {}
        advanceUntilIdle()

        coVerify { taskRepository.save(match<SyncTask> { it.requiresCharging }) }
    }

    @Test
    fun load_newTask_seedsRequiresChargingFromPrefs() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel(AppPreferences(requireChargingByDefault = true))
        vm.load(taskId = 0)
        advanceUntilIdle()

        assertThat(vm.form.value.requiresCharging).isTrue()
    }

    @Test
    fun save_existingTask_preservesOriginalCreatedAt() = runTest(mainDispatcher.dispatcher) {
        val original = SyncTask(
            id = 7,
            name = "Photos",
            sourcePath = "/storage/emulated/0/DCIM",
            remoteName = "gdrive",
            remotePath = "Backups",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            createdAtEpochMs = 1000L,
        )
        coEvery { taskRepository.getTask(7) } returns original
        coEvery { taskRepository.save(any()) } returns 7L
        val vm = viewModel()
        vm.load(taskId = 7)
        advanceUntilIdle()

        vm.update { it.copy(name = "Photos edited") }
        vm.save {}
        advanceUntilIdle()

        // Editing must not reset the creation timestamp.
        coVerify {
            taskRepository.save(match<SyncTask> { it.id == 7L && it.createdAtEpochMs == 1000L })
        }
    }

    @Test
    fun load_negativeIdLeavesBlankForm() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.load(taskId = -1)
        advanceUntilIdle()

        // No task is loaded (negative id). New-task defaults are seeded from app
        // prefs (WS2.0); prefsRepo() supplies AppPreferences() whose metered bw
        // default is "1M", so the otherwise-blank form carries that one value.
        assertThat(vm.form.value).isEqualTo(SyncTaskForm(bwLimitMetered = "1M"))
        coVerify(exactly = 0) { taskRepository.getTask(any()) }
    }

    @Test
    fun load_existingTaskHydratesForm() = runTest(mainDispatcher.dispatcher) {
        val entity = SyncTask(
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

    // --- bwlimit timetable validator (B6, Part 1) ---------------------------

    @Test
    fun bwLimitWifiError_isNullForSingleTokenTimetable() {
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "08:00,512k") }
        assertThat(vm.form.value.bwLimitWifiError).isNull()
    }

    @Test
    fun bwLimitWifiError_isNullForMultiTokenTimetable() {
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "08:00,512k 12:00,10M 22:00,off") }
        assertThat(vm.form.value.bwLimitWifiError).isNull()
    }

    @Test
    fun bwLimitWifiError_isNullForTimetableWithDoubledSpaces() {
        // Pasted input with repeated whitespace between tokens must still validate
        // (split on a whitespace run, not a single space).
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "08:00,512k  22:00,off") }
        assertThat(vm.form.value.bwLimitWifiError).isNull()
    }

    @Test
    fun bwLimitWifiError_isNullForOffToken() {
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "08:00,off") }
        assertThat(vm.form.value.bwLimitWifiError).isNull()
    }

    @Test
    fun bwLimitWifiError_isNullForTimetableWithUpDownRate() {
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "08:00,10M:1M 22:00,off") }
        assertThat(vm.form.value.bwLimitWifiError).isNull()
    }

    @Test
    fun bwLimitWifiError_isSetForInvalidHour_25() {
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "25:00,1M") }
        assertThat(vm.form.value.bwLimitWifiError).isNotNull()
    }

    @Test
    fun bwLimitWifiError_isSetForInvalidMinute_60() {
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "08:60,1M") }
        assertThat(vm.form.value.bwLimitWifiError).isNotNull()
    }

    @Test
    fun bwLimitWifiError_isSetForGarbageRate() {
        val vm = viewModel()
        vm.update { it.copy(bwLimitWifi = "08:00,nonsense") }
        assertThat(vm.form.value.bwLimitWifiError).isNotNull()
    }

    @Test
    fun bwLimitWifiError_isSetForPartiallyInvalidTimetable() {
        val vm = viewModel()
        // First token valid, second invalid
        vm.update { it.copy(bwLimitWifi = "08:00,1M 99:00,off") }
        assertThat(vm.form.value.bwLimitWifiError).isNotNull()
    }

    // --- MaxTransfer validator (B6, Part 2) ---------------------------------

    @Test
    fun maxTransferError_isNullForBlankValue() {
        val vm = viewModel()
        vm.update { it.copy(maxTransfer = "") }
        assertThat(vm.form.value.maxTransferError).isNull()
    }

    @Test
    fun maxTransferError_isNullForValidSizeSuffix() {
        val vm = viewModel()
        listOf("10G", "500M", "1T", "2.5G").forEach { cap ->
            vm.update { it.copy(maxTransfer = cap) }
            assertThat("$cap: ${vm.form.value.maxTransferError}").isEqualTo("$cap: null")
        }
    }

    @Test
    fun maxTransferError_isSetForInvalidSizeSuffix() {
        val vm = viewModel()
        vm.update { it.copy(maxTransfer = "notasize!!") }
        assertThat(vm.form.value.maxTransferError).isNotNull()
    }

    @Test
    fun isValid_isFalseWhenMaxTransferInvalid() {
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "T",
                sourcePath = "/s",
                remoteName = "r",
                remotePath = "dst",
                maxTransfer = "bad!",
            )
        }
        assertThat(vm.form.value.isValid).isFalse()
    }

    @Test
    fun isValid_isTrueWhenMaxTransferBlank() {
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "T",
                sourcePath = "/s",
                remoteName = "r",
                remotePath = "dst",
                maxTransfer = "",
            )
        }
        assertThat(vm.form.value.isValid).isTrue()
    }

    @Test
    fun save_persistsMaxTransfer() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "Capped",
                sourcePath = "/sdcard",
                remoteName = "gdrive",
                remotePath = "Backups",
                maxTransfer = "10G",
            )
        }
        vm.save {}
        advanceUntilIdle()

        coVerify { taskRepository.save(match<SyncTask> { it.maxTransfer == "10G" }) }
    }

    @Test
    fun save_persistsBlankMaxTransferWhenUnset() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "NoCap",
                sourcePath = "/sdcard",
                remoteName = "gdrive",
                remotePath = "Backups",
                maxTransfer = "",
            )
        }
        vm.save {}
        advanceUntilIdle()

        coVerify { taskRepository.save(match<SyncTask> { it.maxTransfer.isBlank() }) }
    }

    @Test
    fun load_existingTask_hydratesMaxTransfer() = runTest(mainDispatcher.dispatcher) {
        val entity = SyncTask(
            id = 20,
            name = "Capped",
            sourcePath = "/sdcard",
            remoteName = "gdrive",
            remotePath = "/dst",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            maxTransfer = "5G",
        )
        coEvery { taskRepository.getTask(20) } returns entity
        val vm = viewModel()
        vm.load(taskId = 20)
        advanceUntilIdle()

        assertThat(vm.form.value.maxTransfer).isEqualTo("5G")
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
                remotePath = "Backups",
                intervalMinutes = -1,
                customIntervalMinutes = 20,
            )
        }

        vm.save {}
        advanceUntilIdle()

        coVerify {
            taskRepository.save(match<SyncTask> { it.intervalMinutes == 20 })
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
                remotePath = "Backups",
                intervalMinutes = 360,
            )
        }

        vm.save {}
        advanceUntilIdle()

        coVerify {
            taskRepository.save(match<SyncTask> { it.intervalMinutes == 360 })
        }
    }

    @Test
    fun save_callsOnSavedAndSchedules() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 55L
        val vm = viewModel()
        vm.update { it.copy(name = "T", sourcePath = "/s", remoteName = "r", remotePath = "dst") }
        var saved = false

        vm.save { saved = true }
        advanceUntilIdle()

        assertThat(saved).isTrue()
        coVerify(exactly = 1) { scheduler.schedule(match<SyncTask> { it.id == 55L }) }
    }

    // --- move/mirror mutual exclusion (0.3.0) --------------------------------

    @Test
    fun enableDeleteSource_whenDeleteExtraneousWasTrue_clearsMirror() {
        val vm = viewModel()
        vm.update { it.copy(deleteExtraneous = true) }
        vm.update { it.copy(deleteSource = true) }

        assertThat(vm.form.value.deleteSource).isTrue()
        assertThat(vm.form.value.deleteExtraneous).isFalse()
    }

    @Test
    fun enableDeleteExtraneous_whenDeleteSourceWasTrue_clearsMove() {
        val vm = viewModel()
        vm.update { it.copy(deleteSource = true) }
        vm.update { it.copy(deleteExtraneous = true) }

        assertThat(vm.form.value.deleteExtraneous).isTrue()
        assertThat(vm.form.value.deleteSource).isFalse()
    }

    @Test
    fun save_normalizes_deleteSource_falseForSafSource() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "Backup",
                sourcePath = "content://tree/primary",
                remoteName = "gdrive",
                remotePath = "Backups",
                deleteSource = true,
            )
        }

        vm.save {}
        advanceUntilIdle()

        coVerify { taskRepository.save(match<SyncTask> { !it.deleteSource }) }
    }

    @Test
    fun save_normalizes_deleteSource_falseForBisync() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "Sync",
                sourcePath = "/sdcard/DCIM",
                remoteName = "gdrive",
                remotePath = "Backups",
                direction = SyncDirection.BISYNC,
                deleteSource = true,
            )
        }

        vm.save {}
        advanceUntilIdle()

        coVerify { taskRepository.save(match<SyncTask> { !it.deleteSource }) }
    }

    @Test
    fun save_persists_deleteSource_trueForNonSafUpload() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "Move",
                sourcePath = "/sdcard/DCIM",
                remoteName = "gdrive",
                remotePath = "Backups",
                direction = SyncDirection.UPLOAD,
                deleteSource = true,
            )
        }

        vm.save {}
        advanceUntilIdle()

        coVerify { taskRepository.save(match<SyncTask> { it.deleteSource }) }
    }

    // --- B5: size / age validators ------------------------------------------

    @Test
    fun minSizeError_isNullForBlankValue() {
        val vm = viewModel()
        vm.update { it.copy(minSize = "") }
        assertThat(vm.form.value.minSizeError).isNull()
    }

    @Test
    fun minSizeError_isNullForValidSizeSuffix() {
        val vm = viewModel()
        listOf("10M", "1.5G", "512", "100k", "2T", "256Mi").forEach { size ->
            vm.update { it.copy(minSize = size) }
            assertThat("$size: ${vm.form.value.minSizeError}").isEqualTo("$size: null")
        }
    }

    @Test
    fun minSizeError_isSetForInvalidSizeSuffix() {
        val vm = viewModel()
        vm.update { it.copy(minSize = "notasize!!") }
        assertThat(vm.form.value.minSizeError).isNotNull()
    }

    @Test
    fun maxAgeError_isNullForBlankValue() {
        val vm = viewModel()
        vm.update { it.copy(maxAge = "") }
        assertThat(vm.form.value.maxAgeError).isNull()
    }

    @Test
    fun maxAgeError_isNullForValidDuration() {
        val vm = viewModel()
        listOf("30d", "1h30m", "100ms", "2w", "1y", "24h").forEach { age ->
            vm.update { it.copy(maxAge = age) }
            assertThat("$age: ${vm.form.value.maxAgeError}").isEqualTo("$age: null")
        }
    }

    @Test
    fun maxAgeError_isSetForInvalidDuration() {
        val vm = viewModel()
        vm.update { it.copy(maxAge = "notaduration!") }
        assertThat(vm.form.value.maxAgeError).isNotNull()
    }

    @Test
    fun isValid_isFalseWhenMinSizeInvalid() {
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "T",
                sourcePath = "/s",
                remoteName = "r",
                remotePath = "dst",
                minSize = "bad!",
            )
        }
        assertThat(vm.form.value.isValid).isFalse()
    }

    @Test
    fun isValid_isTrueWhenSizeAgeBlank() {
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "T",
                sourcePath = "/s",
                remoteName = "r",
                remotePath = "dst",
                minSize = "",
                maxSize = "",
                minAge = "",
                maxAge = "",
            )
        }
        assertThat(vm.form.value.isValid).isTrue()
    }

    @Test
    fun save_persistsSizeAndAgeFields() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "Backup",
                sourcePath = "/sdcard",
                remoteName = "gdrive",
                remotePath = "Backups",
                minSize = "10M",
                maxSize = "2G",
                minAge = "30d",
                maxAge = "1y",
            )
        }
        vm.save {}
        advanceUntilIdle()

        coVerify {
            taskRepository.save(
                match<SyncTask> {
                    it.minSize == "10M" &&
                        it.maxSize == "2G" &&
                        it.minAge == "30d" &&
                        it.maxAge == "1y"
                },
            )
        }
    }

    @Test
    fun load_existingTask_hydratesSizeAgeFields() = runTest(mainDispatcher.dispatcher) {
        val entity = SyncTask(
            id = 11,
            name = "Filtered",
            sourcePath = "/sdcard",
            remoteName = "gdrive",
            remotePath = "/dst",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            minSize = "5M",
            maxSize = "1G",
            minAge = "7d",
            maxAge = "90d",
        )
        coEvery { taskRepository.getTask(11) } returns entity
        val vm = viewModel()
        vm.load(taskId = 11)
        advanceUntilIdle()

        assertThat(vm.form.value.minSize).isEqualTo("5M")
        assertThat(vm.form.value.maxSize).isEqualTo("1G")
        assertThat(vm.form.value.minAge).isEqualTo("7d")
        assertThat(vm.form.value.maxAge).isEqualTo("90d")
    }

    // --- B8: retry config ---------------------------------------------------

    @Test
    fun save_persistsRetryConfig() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "Retry",
                sourcePath = "/sdcard",
                remoteName = "gdrive",
                remotePath = "Backups",
                maxRetries = 5,
                retryOnRclone = true,
                backoffSeconds = 60L,
                backoffExponential = false,
            )
        }
        vm.save {}
        advanceUntilIdle()

        coVerify {
            taskRepository.save(
                match<SyncTask> {
                    it.maxRetries == 5 &&
                        it.retryOnRclone &&
                        it.backoffSeconds == 60L &&
                        !it.backoffExponential
                },
            )
        }
    }

    @Test
    fun load_existingTask_hydratesRetryConfig() = runTest(mainDispatcher.dispatcher) {
        val entity = SyncTask(
            id = 50,
            name = "WithRetry",
            sourcePath = "/sdcard",
            remoteName = "gdrive",
            remotePath = "/dst",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            maxRetries = 7,
            retryOnRclone = true,
            backoffSeconds = 90L,
            backoffExponential = false,
        )
        coEvery { taskRepository.getTask(50) } returns entity
        val vm = viewModel()
        vm.load(taskId = 50)
        advanceUntilIdle()

        assertThat(vm.form.value.maxRetries).isEqualTo(7)
        assertThat(vm.form.value.retryOnRclone).isTrue()
        assertThat(vm.form.value.backoffSeconds).isEqualTo(90L)
        assertThat(vm.form.value.backoffExponential).isFalse()
    }

    @Test
    fun save_maxRetries_clampedToAtLeastOne() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "T",
                sourcePath = "/s",
                remoteName = "r",
                remotePath = "dst",
                maxRetries = 0,
            )
        }
        vm.save {}
        advanceUntilIdle()

        coVerify { taskRepository.save(match<SyncTask> { it.maxRetries >= 1 }) }
    }

    // --- B4: scheduleTimes add / remove ---

    @Test
    fun addScheduleTime_appendsToList() {
        val vm = viewModel()
        vm.addScheduleTime(120)
        vm.addScheduleTime(840)

        assertThat(vm.form.value.scheduleTimes).isEqualTo(listOf(120, 840))
    }

    @Test
    fun addScheduleTime_deduplicate_doesNotAddSameTimeAgain() {
        val vm = viewModel()
        vm.addScheduleTime(120)
        vm.addScheduleTime(120)

        assertThat(vm.form.value.scheduleTimes).isEqualTo(listOf(120))
    }

    @Test
    fun addScheduleTime_clampsToValidRange() {
        val vm = viewModel()
        vm.addScheduleTime(1500) // > 1439

        assertThat(vm.form.value.scheduleTimes).isEqualTo(listOf(1439))
    }

    @Test
    fun removeScheduleTime_removesAtIndex() {
        val vm = viewModel()
        vm.addScheduleTime(120)
        vm.addScheduleTime(840)

        vm.removeScheduleTime(0)

        assertThat(vm.form.value.scheduleTimes).isEqualTo(listOf(840))
    }

    @Test
    fun removeScheduleTime_outOfBoundsIsNoop() {
        val vm = viewModel()
        vm.addScheduleTime(120)

        vm.removeScheduleTime(5) // out of bounds

        assertThat(vm.form.value.scheduleTimes).isEqualTo(listOf(120))
    }

    @Test
    fun save_calendarSchedule_persistsScheduleTimes() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.selectCalendarSchedule()
        vm.update {
            it.copy(
                name = "CalTask",
                sourcePath = "/sdcard/DCIM",
                remoteName = "gdrive",
                remotePath = "Backup",
                scheduleDays = setOf(1, 2, 3, 4, 5, 6, 7),
            )
        }
        vm.addScheduleTime(120)
        vm.addScheduleTime(840)

        vm.save {}
        advanceUntilIdle()

        coVerify {
            taskRepository.save(
                match<SyncTask> { it.scheduleTimes == listOf(120, 840) },
            )
        }
    }

    @Test
    fun save_nonCalendarSchedule_clearsScheduleTimes() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        // Set times, then switch away from calendar.
        vm.selectCalendarSchedule()
        vm.addScheduleTime(120)
        vm.update {
            it.copy(
                name = "T",
                sourcePath = "/s",
                remoteName = "r",
                remotePath = "dst",
                intervalMinutes = 60, // non-calendar interval
            )
        }

        vm.save {}
        advanceUntilIdle()

        coVerify {
            taskRepository.save(match<SyncTask> { it.scheduleTimes.isEmpty() })
        }
    }

    @Test
    fun load_existingCalendarTask_hydratesScheduleTimes() = runTest(mainDispatcher.dispatcher) {
        val entity = SyncTask(
            id = 70,
            name = "Multi",
            sourcePath = "/sdcard",
            remoteName = "gdrive",
            remotePath = "/dst",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = 0,
            scheduleDaysMask = 0x7F,
            scheduleHour = 9,
            scheduleMinute = 0,
            scheduleTimes = listOf(120, 840),
        )
        coEvery { taskRepository.getTask(70) } returns entity
        val vm = viewModel()
        vm.load(taskId = 70)
        advanceUntilIdle()

        assertThat(vm.form.value.scheduleTimes).isEqualTo(listOf(120, 840))
    }

    // --- B10: groupTag and sortOrder ----------------------------------------

    @Test
    fun load_existingTask_hydratesGroupTagAndSortOrder() = runTest(mainDispatcher.dispatcher) {
        val entity = SyncTask(
            id = 80,
            name = "Grouped",
            sourcePath = "/sdcard",
            remoteName = "gdrive",
            remotePath = "/dst",
            direction = SyncDirection.UPLOAD,
            intervalMinutes = null,
            groupTag = "photos",
            sortOrder = 5,
        )
        coEvery { taskRepository.getTask(80) } returns entity
        val vm = viewModel()
        vm.load(taskId = 80)
        advanceUntilIdle()

        assertThat(vm.form.value.groupTag).isEqualTo("photos")
        assertThat(vm.form.value.sortOrder).isEqualTo(5)
        assertThat(vm.form.value.sortOrderText).isEqualTo("5")
    }

    @Test
    fun save_persistsGroupTagAndSortOrder() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "Backup",
                sourcePath = "/sdcard",
                remoteName = "gdrive",
                remotePath = "Backups",
                groupTag = "docs",
                sortOrder = 2,
            )
        }

        vm.save {}
        advanceUntilIdle()

        coVerify {
            taskRepository.save(match<SyncTask> { it.groupTag == "docs" && it.sortOrder == 2 })
        }
    }

    @Test
    fun save_trimsGroupTag() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "Backup",
                sourcePath = "/sdcard",
                remoteName = "gdrive",
                remotePath = "Backups",
                groupTag = "  photos  ",
            )
        }

        vm.save {}
        advanceUntilIdle()

        coVerify { taskRepository.save(match<SyncTask> { it.groupTag == "photos" }) }
    }

    @Test
    fun newTask_defaultGroupTagIsEmpty_defaultSortOrderIsZero() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.load(taskId = 0)
        advanceUntilIdle()

        assertThat(vm.form.value.groupTag).isEmpty()
        assertThat(vm.form.value.sortOrder).isEqualTo(0)
    }

    // --- B7: conflictResolve / conflictCheck --------------------------------

    @Test
    fun load_existingTask_hydratesConflictResolveAndConflictCheck() = runTest(mainDispatcher.dispatcher) {
        val entity = SyncTask(
            id = 90,
            name = "BisyncTask",
            sourcePath = "/sdcard/DCIM",
            remoteName = "gdrive",
            remotePath = "/dst",
            direction = SyncDirection.BISYNC,
            intervalMinutes = null,
            conflictResolve = "newer",
            conflictCheck = true,
        )
        coEvery { taskRepository.getTask(90) } returns entity
        val vm = viewModel()
        vm.load(taskId = 90)
        advanceUntilIdle()

        assertThat(vm.form.value.conflictResolve).isEqualTo("newer")
        assertThat(vm.form.value.conflictCheck).isTrue()
    }

    @Test
    fun save_persistsConflictResolveAndConflictCheck() = runTest(mainDispatcher.dispatcher) {
        coEvery { taskRepository.save(any()) } returns 1L
        val vm = viewModel()
        vm.update {
            it.copy(
                name = "BisyncTask",
                sourcePath = "/sdcard/DCIM",
                remoteName = "gdrive",
                remotePath = "Backups",
                direction = SyncDirection.BISYNC,
                conflictResolve = "older",
                conflictCheck = false,
            )
        }
        vm.save {}
        advanceUntilIdle()

        coVerify {
            taskRepository.save(
                match<SyncTask> { it.conflictResolve == "older" && !it.conflictCheck },
            )
        }
    }

    @Test
    fun newTask_defaultConflictResolveIsEmpty_defaultConflictCheckIsFalse() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.load(taskId = 0)
        advanceUntilIdle()

        assertThat(vm.form.value.conflictResolve).isEmpty()
        assertThat(vm.form.value.conflictCheck).isFalse()
    }

    // --- helpers ------------------------------------------------------------

    private fun task(
        id: Long,
        remoteName: String = "gdrive",
        intervalMinutes: Int? = null,
    ) = SyncTask(
        id = id,
        name = "task-$id",
        sourcePath = "/src",
        remoteName = remoteName,
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = intervalMinutes,
    )
}

private fun prefsRepo(prefs: AppPreferences = AppPreferences()): PreferencesRepository = mockk(relaxed = true) {
    every { preferences } returns flowOf(prefs)
}
