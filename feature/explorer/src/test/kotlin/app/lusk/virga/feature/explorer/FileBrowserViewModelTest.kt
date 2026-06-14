package app.lusk.virga.feature.explorer

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.data.FileBrowserRepository
import app.lusk.virga.core.data.RemoteFolderPickStore
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.rclone.RcloneEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Tests for [FileBrowserViewModel] covering the entries StateFlow (search
 * filter + sort), sort/search state persistence, and selectRemoteIfUnset.
 *
 * Selection, navigation, and error-handling tests live in
 * [FileBrowserSelectionNavTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val remotesFlow = MutableStateFlow<List<Remote>>(emptyList())
    private val engine: RcloneEngine = mockk(relaxed = true)
    private val repository: RemoteRepository = mockk(relaxed = true) {
        every { remotes } returns remotesFlow
    }

    // Route the ViewModel's background work onto the test dispatcher so the
    // load() coroutine's withContext(default) block resolves on the virtual-time
    // scheduler instead of a real thread — otherwise its resume onto Main can
    // outlive runTest and crash a later test after resetMain (flaky leak).
    private val testDispatchers = object : DispatcherProvider {
        override val main = mainDispatcher.dispatcher
        override val default = mainDispatcher.dispatcher
        override val io = mainDispatcher.dispatcher
    }

    private fun viewModel() =
        FileBrowserViewModel(FileBrowserRepository(engine), repository, testDispatchers, RemoteFolderPickStore())

    private fun file(name: String, size: Long = 0L, modMs: Long? = null) =
        FileItem(name = name, path = name, isDir = false, size = size, modTimeEpochMs = modMs)

    private fun dir(name: String, size: Long = 0L, modMs: Long? = null) =
        FileItem(name = name, path = name, isDir = true, size = size, modTimeEpochMs = modMs)

    // -------------------------------------------------------------------------
    // entries — search filter
    // -------------------------------------------------------------------------

    @Test
    fun `entries returns all items when search query is blank`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("a.txt"), file("b.txt"))
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.entries.collect {} }

        vm.selectRemote("myRemote")
        advanceUntilIdle()

        assertThat(vm.entries.value).hasSize(2)
        collector.cancel()
    }

    @Test
    fun `entries filters by search query case-insensitively`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(
            file("Photo.jpg"), file("video.mp4"), file("notes.txt"),
        )
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.entries.collect {} }

        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.setSearchQuery("photo")
        advanceUntilIdle()

        assertThat(vm.entries.value).hasSize(1)
        assertThat(vm.entries.value[0].name).isEqualTo("Photo.jpg")
        collector.cancel()
    }

    @Test
    fun `entries returns empty list when no items match search query`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("report.pdf"), file("image.png"))
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.entries.collect {} }

        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.setSearchQuery("zzz_no_match")
        advanceUntilIdle()

        assertThat(vm.entries.value).isEmpty()
        collector.cancel()
    }

    // -------------------------------------------------------------------------
    // entries — sort by NAME
    // -------------------------------------------------------------------------

    @Test
    fun `entries sorts files by name ASC with directories pinned first`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(
            file("zebra.txt"), dir("alpha"), file("mango.txt"), dir("beta"),
        )
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.entries.collect {} }

        vm.selectRemote("remote")
        advanceUntilIdle()

        assertThat(vm.entries.value.map { it.name })
            .containsExactly("alpha", "beta", "mango.txt", "zebra.txt").inOrder()
        collector.cancel()
    }

    @Test
    fun `entries sorts files by name DESC with directories still pinned first`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(
            file("apple.txt"), dir("zeta"), file("mango.txt"), dir("alpha"),
        )
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.entries.collect {} }

        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.setSortConfig(SortConfig(SortField.NAME, SortOrder.DESC))
        advanceUntilIdle()

        assertThat(vm.entries.value.map { it.name })
            .containsExactly("zeta", "alpha", "mango.txt", "apple.txt").inOrder()
        collector.cancel()
    }

    // -------------------------------------------------------------------------
    // entries — sort by SIZE
    // -------------------------------------------------------------------------

    @Test
    fun `entries sorts files by size ASC with directories pinned first`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(
            file("large.zip", size = 1000L), dir("folder"), file("small.txt", size = 10L),
        )
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.entries.collect {} }

        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.setSortConfig(SortConfig(SortField.SIZE, SortOrder.ASC))
        advanceUntilIdle()

        assertThat(vm.entries.value.map { it.name })
            .containsExactly("folder", "small.txt", "large.zip").inOrder()
        collector.cancel()
    }

    @Test
    fun `entries sorts files by size DESC with directories pinned first`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(
            file("small.txt", size = 10L), dir("folder"), file("large.zip", size = 1000L),
        )
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.entries.collect {} }

        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.setSortConfig(SortConfig(SortField.SIZE, SortOrder.DESC))
        advanceUntilIdle()

        assertThat(vm.entries.value.map { it.name })
            .containsExactly("folder", "large.zip", "small.txt").inOrder()
        collector.cancel()
    }

    // -------------------------------------------------------------------------
    // entries — sort by MODIFIED
    // -------------------------------------------------------------------------

    @Test
    fun `entries sorts files by modified ASC with null modTime treated as 0`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(
            file("recent.txt", modMs = 5000L),
            file("no-time.txt", modMs = null),
            file("old.txt", modMs = 1000L),
        )
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.entries.collect {} }

        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.setSortConfig(SortConfig(SortField.MODIFIED, SortOrder.ASC))
        advanceUntilIdle()

        assertThat(vm.entries.value.map { it.name })
            .containsExactly("no-time.txt", "old.txt", "recent.txt").inOrder()
        collector.cancel()
    }

    @Test
    fun `entries sorts files by modified DESC with null modTime treated as 0`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(
            file("recent.txt", modMs = 5000L),
            file("no-time.txt", modMs = null),
            file("old.txt", modMs = 1000L),
        )
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.entries.collect {} }

        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.setSortConfig(SortConfig(SortField.MODIFIED, SortOrder.DESC))
        advanceUntilIdle()

        assertThat(vm.entries.value.map { it.name })
            .containsExactly("recent.txt", "old.txt", "no-time.txt").inOrder()
        collector.cancel()
    }

    @Test
    fun `entries keeps all directories before all files regardless of sort direction`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(
            file("file-old.txt", modMs = 100L),
            dir("dir-new", modMs = 9000L),
            dir("dir-old", modMs = 100L),
            file("file-new.txt", modMs = 9000L),
        )
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.entries.collect {} }

        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.setSortConfig(SortConfig(SortField.MODIFIED, SortOrder.DESC))
        advanceUntilIdle()

        val result = vm.entries.value
        val firstFileIdx = result.indexOfFirst { !it.isDir }
        val lastDirIdx = result.indexOfLast { it.isDir }
        assertThat(lastDirIdx).isLessThan(firstFileIdx)
        collector.cancel()
    }

    // -------------------------------------------------------------------------
    // createFolder
    // -------------------------------------------------------------------------

    @Test
    fun `createFolder creates the joined child path then navigates into it`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        coEvery { engine.mkdir(any(), any()) } returns Unit
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        vm.open(dir("Photos").copy(path = "Photos"))
        advanceUntilIdle()

        vm.openCreateFolderDialog()
        vm.createFolder("2024")
        advanceUntilIdle()

        // mkdir is called with the joined "<current path>/<name>" via the repository
        // ("$remote:" prefix added by the engine boundary).
        coVerify { engine.mkdir("gdrive:", "Photos/2024") }
        // Navigated into the freshly created folder and the dialog closed.
        assertThat(vm.state.value.path).isEqualTo("Photos/2024")
        assertThat(vm.state.value.remoteName).isEqualTo("gdrive")
        assertThat(vm.state.value.showCreateFolderDialog).isFalse()
        assertThat(vm.state.value.creatingFolder).isFalse()
        assertThat(vm.state.value.createFolderError).isNull()
    }

    @Test
    fun `createFolder at root joins without a leading slash`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        coEvery { engine.mkdir(any(), any()) } returns Unit
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.createFolder("NewFolder")
        advanceUntilIdle()

        coVerify { engine.mkdir("gdrive:", "NewFolder") }
        assertThat(vm.state.value.path).isEqualTo("NewFolder")
    }

    @Test
    fun `createFolder rejects a blank name without calling mkdir`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.createFolder("   ")
        advanceUntilIdle()

        assertThat(vm.state.value.createFolderError).isEqualTo(R.string.explorer_new_folder_invalid_name)
        coVerify(exactly = 0) { engine.mkdir(any(), any()) }
    }

    @Test
    fun `createFolder rejects a name with a slash without calling mkdir`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.createFolder("a/b")
        advanceUntilIdle()

        assertThat(vm.state.value.createFolderError).isEqualTo(R.string.explorer_new_folder_invalid_name)
        coVerify(exactly = 0) { engine.mkdir(any(), any()) }
    }

    @Test
    fun `createFolder rejects the parent-dir name without calling mkdir`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.createFolder("..")
        advanceUntilIdle()

        assertThat(vm.state.value.createFolderError).isEqualTo(R.string.explorer_new_folder_invalid_name)
        coVerify(exactly = 0) { engine.mkdir(any(), any()) }
    }

    @Test
    fun `createFolder rejects a case-insensitive duplicate without calling mkdir`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(dir("Photos"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.createFolder("photos")
        advanceUntilIdle()

        assertThat(vm.state.value.createFolderError).isEqualTo(R.string.explorer_new_folder_exists)
        coVerify(exactly = 0) { engine.mkdir(any(), any()) }
    }

    @Test
    fun `createFolder rejects a name with a backslash without calling mkdir`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.createFolder("a\\b")
        advanceUntilIdle()

        assertThat(vm.state.value.createFolderError).isEqualTo(R.string.explorer_new_folder_invalid_name)
        coVerify(exactly = 0) { engine.mkdir(any(), any()) }
    }

    @Test
    fun `createFolder rejects a name with a control character without calling mkdir`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.createFolder("a\tb")
        advanceUntilIdle()

        assertThat(vm.state.value.createFolderError).isEqualTo(R.string.explorer_new_folder_invalid_name)
        coVerify(exactly = 0) { engine.mkdir(any(), any()) }
    }

    @Test
    fun `createFolder rejects an over-length name without calling mkdir`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.createFolder("a".repeat(256))
        advanceUntilIdle()

        assertThat(vm.state.value.createFolderError).isEqualTo(R.string.explorer_new_folder_invalid_name)
        coVerify(exactly = 0) { engine.mkdir(any(), any()) }
    }

    @Test
    fun `createFolder surfaces an error and does not navigate when mkdir fails`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        coEvery { engine.mkdir(any(), any()) } throws
            app.lusk.virga.core.common.error.VirgaError.Rclone(message = "boom")
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.openCreateFolderDialog()
        vm.createFolder("NewFolder")
        advanceUntilIdle()

        assertThat(vm.state.value.createFolderError).isEqualTo(R.string.explorer_new_folder_failed)
        assertThat(vm.state.value.creatingFolder).isFalse()
        // Stayed in the current directory; the dialog remains open to allow a retry.
        assertThat(vm.state.value.path).isEqualTo("")
        assertThat(vm.state.value.showCreateFolderDialog).isTrue()
    }

    // -------------------------------------------------------------------------
    // selectRemoteIfUnset
    // -------------------------------------------------------------------------

    @Test
    fun `selectRemoteIfUnset selects remote when none is set`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()

        vm.selectRemoteIfUnset("myRemote")
        advanceUntilIdle()

        assertThat(vm.state.value.remoteName).isEqualTo("myRemote")
    }

    @Test
    fun `selectRemoteIfUnset is a no-op when a remote is already set`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("firstRemote")
        advanceUntilIdle()

        vm.selectRemoteIfUnset("secondRemote")
        advanceUntilIdle()

        assertThat(vm.state.value.remoteName).isEqualTo("firstRemote")
    }

    @Test
    fun `selectRemoteIfUnset is idempotent across multiple calls`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()

        vm.selectRemoteIfUnset("alpha")
        advanceUntilIdle()
        vm.selectRemoteIfUnset("beta")
        advanceUntilIdle()
        vm.selectRemoteIfUnset("gamma")
        advanceUntilIdle()

        assertThat(vm.state.value.remoteName).isEqualTo("alpha")
    }

    // -------------------------------------------------------------------------
    // Sort/search state persistence
    // -------------------------------------------------------------------------

    @Test
    fun `sort config survives folder navigation within the same remote`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.setSortConfig(SortConfig(SortField.SIZE, SortOrder.DESC))

        vm.open(dir("sub", 0L).copy(path = "sub"))
        advanceUntilIdle()

        assertThat(vm.state.value.sortConfig).isEqualTo(SortConfig(SortField.SIZE, SortOrder.DESC))
    }

    @Test
    fun `search query is cleared when navigating into a subdirectory`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.setSearchQuery("report")

        vm.open(dir("reports", 0L).copy(path = "reports"))
        advanceUntilIdle()

        assertThat(vm.state.value.searchQuery).isEmpty()
        assertThat(vm.state.value.searchActive).isFalse()
    }

    @Test
    fun `sort config resets when switching to a different remote`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("remoteA")
        advanceUntilIdle()
        vm.setSortConfig(SortConfig(SortField.MODIFIED, SortOrder.DESC))

        vm.selectRemote("remoteB")
        advanceUntilIdle()

        assertThat(vm.state.value.sortConfig).isEqualTo(SortConfig())
    }

    @Test
    fun `search query resets when switching to a different remote`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("remoteA")
        advanceUntilIdle()
        vm.setSearchQuery("old-query")

        vm.selectRemote("remoteB")
        advanceUntilIdle()

        assertThat(vm.state.value.searchQuery).isEmpty()
    }
}
