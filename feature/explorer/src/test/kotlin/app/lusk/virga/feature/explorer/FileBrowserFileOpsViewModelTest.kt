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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Tests for the file-operation methods on [FileBrowserViewModel]:
 * deleteSelected, rename, moveSelected, copySelected and their dialog-state helpers.
 *
 * Sort/search/navigation tests live in [FileBrowserViewModelTest] and
 * [FileBrowserSelectionNavTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserFileOpsViewModelTest {

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

    private fun dir(name: String, path: String = name) =
        FileItem(name = name, path = path, isDir = true, size = 0L, modTimeEpochMs = null)

    // -------------------------------------------------------------------------
    // deleteSelected — file vs directory routing
    // -------------------------------------------------------------------------

    @Test
    fun `deleteSelected routes file items to deleteFile`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("img.jpg"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("img.jpg")

        vm.deleteSelected()
        advanceUntilIdle()

        coVerify { engine.deleteFile("gdrive:", "img.jpg") }
        coVerify(exactly = 0) { engine.purge(any(), any()) }
    }

    @Test
    fun `deleteSelected routes directory items to purge`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(dir("OldPhotos"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("OldPhotos")

        vm.deleteSelected()
        advanceUntilIdle()

        coVerify { engine.purge("gdrive:", "OldPhotos") }
        coVerify(exactly = 0) { engine.deleteFile(any(), any()) }
    }

    @Test
    fun `deleteSelected clears selection and refreshes listing on success`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("note.txt"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("note.txt")

        vm.deleteSelected()
        advanceUntilIdle()

        assertThat(vm.state.value.selectionMode).isFalse()
        assertThat(vm.state.value.selectedPaths).isEmpty()
        // list called twice: once on selectRemote, once after delete refresh.
        coVerify(exactly = 2) { engine.listDir(any(), any()) }
    }

    @Test
    fun `deleteSelected surfaces error via statusMessage on failure`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("locked.txt"))
        coEvery { engine.deleteFile(any(), any()) } throws VirgaError.Rclone(message = "permission denied")
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("locked.txt")

        vm.deleteSelected()
        advanceUntilIdle()

        assertThat(vm.state.value.statusMessage).isNotNull()
        // fileOpInProgress must be cleared even on failure.
        assertThat(vm.state.value.fileOpInProgress).isFalse()
        // listing must refresh and selection must clear even after a failure.
        assertThat(vm.state.value.selectionMode).isFalse()
        coVerify(exactly = 2) { engine.listDir(any(), any()) }
    }

    @Test
    fun `deleteSelected partial failure reports count and still refreshes listing`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("ok.txt"), file("locked.txt"))
        coEvery { engine.deleteFile("gdrive:", "ok.txt") } returns Unit
        coEvery { engine.deleteFile("gdrive:", "locked.txt") } throws VirgaError.Rclone(message = "denied")
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("ok.txt")
        vm.toggleSelection("locked.txt")

        vm.deleteSelected()
        advanceUntilIdle()

        // Both items were attempted.
        coVerify { engine.deleteFile("gdrive:", "ok.txt") }
        coVerify { engine.deleteFile("gdrive:", "locked.txt") }
        // Partial failure message is set.
        assertThat(vm.state.value.statusMessage).isNotNull()
        // Listing is always refreshed.
        coVerify(exactly = 2) { engine.listDir(any(), any()) }
        // Selection is always cleared.
        assertThat(vm.state.value.selectionMode).isFalse()
    }

    @Test
    fun `deleteSelected is a no-op when selection is empty`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.deleteSelected()
        advanceUntilIdle()

        coVerify(exactly = 0) { engine.deleteFile(any(), any()) }
        coVerify(exactly = 0) { engine.purge(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Delete dialog state
    // -------------------------------------------------------------------------

    @Test
    fun `openDeleteConfirmDialog shows dialog when items are selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.enterSelectionMode("file.txt")
        vm.openDeleteConfirmDialog()

        assertThat(vm.state.value.showDeleteConfirmDialog).isTrue()
    }

    @Test
    fun `openDeleteConfirmDialog is a no-op when nothing selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.openDeleteConfirmDialog()

        assertThat(vm.state.value.showDeleteConfirmDialog).isFalse()
    }

    @Test
    fun `dismissDeleteConfirmDialog hides dialog`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.enterSelectionMode("file.txt")
        vm.openDeleteConfirmDialog()
        vm.dismissDeleteConfirmDialog()

        assertThat(vm.state.value.showDeleteConfirmDialog).isFalse()
    }

    // -------------------------------------------------------------------------
    // rename
    // -------------------------------------------------------------------------

    @Test
    fun `rename moves the item to the same parent with the new name`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.rename("Photos/old.jpg", "new.jpg")
        advanceUntilIdle()

        coVerify { engine.moveFile("gdrive:Photos/old.jpg", "gdrive:Photos/new.jpg") }
    }

    @Test
    fun `rename at root produces a root-level destination`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.rename("oldfile.txt", "newfile.txt")
        advanceUntilIdle()

        coVerify { engine.moveFile("gdrive:oldfile.txt", "gdrive:newfile.txt") }
    }

    @Test
    fun `rename with invalid name sets renameError and skips moveFile`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.rename("Photos/img.jpg", "bad/name")
        advanceUntilIdle()

        assertThat(vm.state.value.renameError).isEqualTo(R.string.explorer_rename_invalid_name)
        coVerify(exactly = 0) { engine.moveFile(any(), any()) }
    }

    @Test
    fun `rename rejects a name that collides with an existing sibling`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(
            file("existing.txt", "existing.txt"),
            file("old.txt", "old.txt"),
        )
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        // Attempt to rename old.txt -> existing.txt (collision).
        vm.rename("old.txt", "existing.txt")
        advanceUntilIdle()

        assertThat(vm.state.value.renameError).isEqualTo(R.string.explorer_new_folder_exists)
        coVerify(exactly = 0) { engine.moveFile(any(), any()) }
    }

    @Test
    fun `rename allows keeping the same name (excludes self from collision check)`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("photo.jpg", "photo.jpg"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        // "Renaming" to the exact same name should not be blocked by collision.
        vm.rename("photo.jpg", "photo.jpg")
        advanceUntilIdle()

        assertThat(vm.state.value.renameError).isNull()
        coVerify { engine.moveFile(any(), any()) }
    }

    @Test
    fun `rename clears selection and refreshes on success`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("file.txt")

        vm.rename("file.txt", "renamed.txt")
        advanceUntilIdle()

        assertThat(vm.state.value.selectionMode).isFalse()
        assertThat(vm.state.value.selectedPaths).isEmpty()
        coVerify(exactly = 2) { engine.listDir(any(), any()) }
    }

    @Test
    fun `rename surfaces statusMessage on engine failure`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        coEvery { engine.moveFile(any(), any()) } throws VirgaError.Rclone(message = "network error")
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.rename("file.txt", "other.txt")
        advanceUntilIdle()

        assertThat(vm.state.value.statusMessage).isNotNull()
        assertThat(vm.state.value.fileOpInProgress).isFalse()
    }

    // -------------------------------------------------------------------------
    // Rename dialog state
    // -------------------------------------------------------------------------

    @Test
    fun `openRenameDialog sets showRenameDialog and renamePath`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.openRenameDialog("Photos/img.jpg")

        assertThat(vm.state.value.showRenameDialog).isTrue()
        assertThat(vm.state.value.renamePath).isEqualTo("Photos/img.jpg")
    }

    @Test
    fun `dismissRenameDialog clears dialog state and error`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.openRenameDialog("Photos/img.jpg")
        vm.dismissRenameDialog()

        assertThat(vm.state.value.showRenameDialog).isFalse()
        assertThat(vm.state.value.renamePath).isNull()
        assertThat(vm.state.value.renameError).isNull()
    }

    // -------------------------------------------------------------------------
    // moveSelected
    // -------------------------------------------------------------------------

    @Test
    fun `moveSelected calls moveFile for each selected item into destDir`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("a.txt"), file("b.txt"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("a.txt")
        vm.toggleSelection("b.txt")

        vm.moveSelected("Archive")
        advanceUntilIdle()

        coVerify { engine.moveFile("gdrive:a.txt", "gdrive:Archive/a.txt") }
        coVerify { engine.moveFile("gdrive:b.txt", "gdrive:Archive/b.txt") }
    }

    @Test
    fun `moveSelected with empty destDir moves to root`() = runTest(mainDispatcher.dispatcher) {
        // Name is just the basename; path is the full path within the remote.
        val item = FileItem(name = "report.pdf", path = "docs/report.pdf", isDir = false, size = 0L, modTimeEpochMs = null)
        coEvery { engine.listDir(any(), any()) } returns listOf(item)
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("docs/report.pdf")

        vm.moveSelected("")
        advanceUntilIdle()

        coVerify { engine.moveFile("gdrive:docs/report.pdf", "gdrive:report.pdf") }
    }

    @Test
    fun `moveSelected clears selection and refreshes on success`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("note.txt"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("note.txt")

        vm.moveSelected("Archive")
        advanceUntilIdle()

        assertThat(vm.state.value.selectionMode).isFalse()
        assertThat(vm.state.value.selectedPaths).isEmpty()
    }

    @Test
    fun `moveSelected surfaces statusMessage on failure and still refreshes listing`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("note.txt"))
        coEvery { engine.moveFile(any(), any()) } throws VirgaError.Rclone(message = "quota exceeded")
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("note.txt")

        vm.moveSelected("Archive")
        advanceUntilIdle()

        assertThat(vm.state.value.statusMessage).isNotNull()
        assertThat(vm.state.value.fileOpInProgress).isFalse()
        // Listing is always refreshed even on failure.
        coVerify(exactly = 2) { engine.listDir(any(), any()) }
        assertThat(vm.state.value.selectionMode).isFalse()
    }

    @Test
    fun `moveSelected strips leading and trailing slashes from destDir`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("img.jpg"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("img.jpg")

        vm.moveSelected("/Photos/2024/")
        advanceUntilIdle()

        coVerify { engine.moveFile("gdrive:img.jpg", "gdrive:Photos/2024/img.jpg") }
    }

    // -------------------------------------------------------------------------
    // copySelected
    // -------------------------------------------------------------------------

    @Test
    fun `copySelected calls copyFile for each selected item into destDir`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("img.jpg"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("img.jpg")

        vm.copySelected("Backup")
        advanceUntilIdle()

        coVerify { engine.copyFile("gdrive:img.jpg", "gdrive:Backup/img.jpg") }
    }

    @Test
    fun `copySelected with empty destDir copies to root`() = runTest(mainDispatcher.dispatcher) {
        // Name is just the basename; path is the full path within the remote.
        val item = FileItem(name = "doc.txt", path = "sub/doc.txt", isDir = false, size = 0L, modTimeEpochMs = null)
        coEvery { engine.listDir(any(), any()) } returns listOf(item)
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("sub/doc.txt")

        vm.copySelected("")
        advanceUntilIdle()

        coVerify { engine.copyFile("gdrive:sub/doc.txt", "gdrive:doc.txt") }
    }

    @Test
    fun `copySelected clears selection and refreshes on success`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("file.txt"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("file.txt")

        vm.copySelected("Copy")
        advanceUntilIdle()

        assertThat(vm.state.value.selectionMode).isFalse()
    }

    @Test
    fun `copySelected surfaces statusMessage on failure and still refreshes listing`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("file.txt"))
        coEvery { engine.copyFile(any(), any()) } throws VirgaError.Rclone(message = "insufficient storage")
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("file.txt")

        vm.copySelected("Backup")
        advanceUntilIdle()

        assertThat(vm.state.value.statusMessage).isNotNull()
        assertThat(vm.state.value.fileOpInProgress).isFalse()
        // Listing is always refreshed even on failure.
        coVerify(exactly = 2) { engine.listDir(any(), any()) }
        assertThat(vm.state.value.selectionMode).isFalse()
    }

    @Test
    fun `copySelected strips leading and trailing slashes from destDir`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("doc.txt"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("doc.txt")

        vm.copySelected("Backup/")
        advanceUntilIdle()

        coVerify { engine.copyFile("gdrive:doc.txt", "gdrive:Backup/doc.txt") }
    }

    // -------------------------------------------------------------------------
    // Move / Copy dialog state
    // -------------------------------------------------------------------------

    @Test
    fun `openMoveDialog shows dialog when items selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.enterSelectionMode("file.txt")
        vm.openMoveDialog()

        assertThat(vm.state.value.showMoveDialog).isTrue()
    }

    @Test
    fun `openMoveDialog is a no-op when nothing selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.openMoveDialog()

        assertThat(vm.state.value.showMoveDialog).isFalse()
    }

    @Test
    fun `dismissMoveDialog hides dialog`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.enterSelectionMode("file.txt")
        vm.openMoveDialog()
        vm.dismissMoveDialog()

        assertThat(vm.state.value.showMoveDialog).isFalse()
    }

    @Test
    fun `openCopyDialog shows dialog when items selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.enterSelectionMode("file.txt")
        vm.openCopyDialog()

        assertThat(vm.state.value.showCopyDialog).isTrue()
    }

    @Test
    fun `dismissCopyDialog hides dialog`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.enterSelectionMode("file.txt")
        vm.openCopyDialog()
        vm.dismissCopyDialog()

        assertThat(vm.state.value.showCopyDialog).isFalse()
    }

    // -------------------------------------------------------------------------
    // clearStatusMessage
    // -------------------------------------------------------------------------

    @Test
    fun `clearStatusMessage nulls out the statusMessage`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("locked.txt"))
        coEvery { engine.deleteFile(any(), any()) } throws VirgaError.Rclone(message = "error")
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.enterSelectionMode("locked.txt")
        vm.deleteSelected()
        advanceUntilIdle()
        assertThat(vm.state.value.statusMessage).isNotNull()

        vm.clearStatusMessage()

        assertThat(vm.state.value.statusMessage).isNull()
    }
}
