package app.lusk.virga.feature.explorer

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.data.FileBrowserRepository
import app.lusk.virga.core.data.RemoteFolderPickStore
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.rclone.RcloneEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File

/**
 * Unit tests for [FileBrowserViewModel] transfer methods:
 * [FileBrowserViewModel.downloadForAction] and [FileBrowserViewModel.uploadLocalFile].
 *
 * Intent firing (ACTION_VIEW / ACTION_SEND) is the caller's responsibility;
 * these tests verify ViewModel state transitions only — no real intents are fired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserTransferViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val remotesFlow = MutableStateFlow<List<Remote>>(emptyList())
    private val engine: RcloneEngine = mockk(relaxed = true)
    private val repository: RemoteRepository = mockk(relaxed = true) {
        every { remotes } returns remotesFlow
    }

    private val testDispatchers = object : DispatcherProvider {
        override val main = mainDispatcher.dispatcher
        override val default = mainDispatcher.dispatcher
        override val io = mainDispatcher.dispatcher
    }

    private fun viewModel() =
        FileBrowserViewModel(FileBrowserRepository(engine), repository, testDispatchers, RemoteFolderPickStore())

    private fun file(name: String, path: String = name) =
        FileItem(name = name, path = path, isDir = false, size = 0L, modTimeEpochMs = null)

    // -------------------------------------------------------------------------
    // showActionSheet / dismissActionSheet
    // -------------------------------------------------------------------------

    @Test
    fun `showActionSheet sets actionSheetItem`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val item = file("photo.jpg")

        vm.showActionSheet(item)

        assertThat(vm.state.value.actionSheetItem).isEqualTo(item)
    }

    @Test
    fun `dismissActionSheet clears actionSheetItem`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.showActionSheet(file("photo.jpg"))

        vm.dismissActionSheet()

        assertThat(vm.state.value.actionSheetItem).isNull()
    }

    // -------------------------------------------------------------------------
    // downloadForAction — happy path
    // -------------------------------------------------------------------------

    @Test
    fun `downloadForAction sets transferInProgress true then false and invokes onReady`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        coEvery { engine.downloadFile(any(), any(), any(), any()) } returns Unit
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        var readyFile: File? = null
        val item = file("img.jpg", "Photos/img.jpg")
        vm.downloadForAction(item, File("/tmp")) { readyFile = it }
        advanceUntilIdle()

        assertThat(vm.state.value.transferInProgress).isFalse()
        assertThat(readyFile).isNotNull()
    }

    @Test
    fun `downloadForAction clears actionSheetItem immediately on start`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        coEvery { engine.downloadFile(any(), any(), any(), any()) } returns Unit
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        val item = file("img.jpg")
        vm.showActionSheet(item)

        vm.downloadForAction(item, File("/tmp")) {}
        advanceUntilIdle()

        assertThat(vm.state.value.actionSheetItem).isNull()
    }

    // -------------------------------------------------------------------------
    // downloadForAction — failure path
    // -------------------------------------------------------------------------

    @Test
    fun `downloadForAction surfaces statusMessage and clears transferInProgress on failure`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        coEvery { engine.downloadFile(any(), any(), any(), any()) } throws VirgaError.Rclone(message = "quota exceeded")
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.downloadForAction(file("big.zip", "big.zip"), File("/tmp")) {}
        advanceUntilIdle()

        assertThat(vm.state.value.transferInProgress).isFalse()
        assertThat(vm.state.value.statusMessage).isNotNull()
    }

    @Test
    fun `downloadForAction is a no-op when no remote is selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()

        var onReadyCalled = false
        vm.downloadForAction(file("img.jpg"), File("/tmp")) { onReadyCalled = true }
        advanceUntilIdle()

        assertThat(onReadyCalled).isFalse()
        assertThat(vm.state.value.transferInProgress).isFalse()
    }

    @Test
    fun `downloadForAction is a no-op when transfer is already in progress`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        coEvery { engine.downloadFile(any(), any(), any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        // First call starts the transfer but never completes (awaitCancellation).
        vm.downloadForAction(file("a.jpg"), File("/tmp")) {}
        advanceUntilIdle()

        var secondOnReadyCalled = false
        vm.downloadForAction(file("b.jpg"), File("/tmp")) { secondOnReadyCalled = true }
        advanceUntilIdle()

        // Second call must not fire onReady because transferInProgress is true.
        assertThat(secondOnReadyCalled).isFalse()
    }

    // -------------------------------------------------------------------------
    // uploadLocalFile — happy path
    // -------------------------------------------------------------------------

    @Test
    fun `uploadLocalFile sets transferInProgress true then false and refreshes listing`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        coEvery { engine.uploadFile(any(), any(), any(), any()) } returns Unit
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.uploadLocalFile(File("/tmp/shared/notes.txt"))
        advanceUntilIdle()

        assertThat(vm.state.value.transferInProgress).isFalse()
        // listDir called twice: selectRemote + post-upload refresh.
        io.mockk.coVerify(exactly = 2) { engine.listDir(any(), any()) }
    }

    @Test
    fun `uploadLocalFile places file into current path on remote`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val destPathSlot = slot<String>()
        coEvery { engine.uploadFile(any(), any(), any(), capture(destPathSlot)) } returns Unit
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        // Navigate into a subdirectory.
        vm.open(FileItem(name = "Photos", path = "Photos", isDir = true, size = 0L, modTimeEpochMs = null))
        advanceUntilIdle()

        vm.uploadLocalFile(File("/tmp/shared/vacation.jpg"))
        advanceUntilIdle()

        assertThat(destPathSlot.captured).isEqualTo("Photos/vacation.jpg")
    }

    // -------------------------------------------------------------------------
    // uploadLocalFile — failure path
    // -------------------------------------------------------------------------

    @Test
    fun `uploadLocalFile surfaces statusMessage and clears transferInProgress on failure`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        coEvery { engine.uploadFile(any(), any(), any(), any()) } throws VirgaError.Rclone(message = "storage full")
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.uploadLocalFile(File("/tmp/shared/archive.zip"))
        advanceUntilIdle()

        assertThat(vm.state.value.transferInProgress).isFalse()
        assertThat(vm.state.value.statusMessage).isNotNull()
    }

    @Test
    fun `uploadLocalFile is a no-op when no remote is selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()

        vm.uploadLocalFile(File("/tmp/shared/file.txt"))
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 0) { engine.uploadFile(any(), any(), any(), any()) }
        assertThat(vm.state.value.transferInProgress).isFalse()
    }
}
