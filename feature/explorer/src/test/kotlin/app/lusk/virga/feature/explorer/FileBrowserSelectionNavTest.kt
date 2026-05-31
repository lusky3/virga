package app.lusk.virga.feature.explorer

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.data.FileBrowserRepository
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.database.entity.RemoteEntity
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
 * Tests for [FileBrowserViewModel] covering selection mode, back navigation
 * (up/open), toggleSearch, retry, and error handling.
 *
 * Sorting/filtering and state-persistence tests live in [FileBrowserViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserSelectionNavTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val remotesFlow = MutableStateFlow<List<RemoteEntity>>(emptyList())
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
        FileBrowserViewModel(FileBrowserRepository(engine), repository, testDispatchers)

    private fun file(name: String, path: String = name) =
        FileItem(name = name, path = path, isDir = false, size = 0L, modTimeEpochMs = null)

    private fun dir(name: String, path: String = name) =
        FileItem(name = name, path = path, isDir = true, size = 0L, modTimeEpochMs = null)

    // -------------------------------------------------------------------------
    // Selection mode — enter / toggle / clear
    // -------------------------------------------------------------------------

    @Test
    fun `enterSelectionMode enters selection mode with single path selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()

        vm.enterSelectionMode("path/to/file.txt")

        assertThat(vm.state.value.selectionMode).isTrue()
        assertThat(vm.state.value.selectedPaths).containsExactly("path/to/file.txt")
    }

    @Test
    fun `toggleSelection adds path when not currently selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.enterSelectionMode("first.txt")

        vm.toggleSelection("second.txt")

        assertThat(vm.state.value.selectedPaths).containsExactly("first.txt", "second.txt")
    }

    @Test
    fun `toggleSelection removes path when already selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.enterSelectionMode("file.txt")

        vm.toggleSelection("file.txt")

        assertThat(vm.state.value.selectedPaths).isEmpty()
    }

    @Test
    fun `toggleSelection exits selection mode when last item is deselected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.enterSelectionMode("file.txt")

        vm.toggleSelection("file.txt")

        assertThat(vm.state.value.selectionMode).isFalse()
    }

    @Test
    fun `toggleSelection keeps selection mode active when items remain selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.enterSelectionMode("a.txt")
        vm.toggleSelection("b.txt")

        vm.toggleSelection("a.txt")

        assertThat(vm.state.value.selectionMode).isTrue()
        assertThat(vm.state.value.selectedPaths).containsExactly("b.txt")
    }

    @Test
    fun `clearSelection exits selection mode and empties selected paths`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.enterSelectionMode("file.txt")
        vm.toggleSelection("other.txt")

        vm.clearSelection()

        assertThat(vm.state.value.selectionMode).isFalse()
        assertThat(vm.state.value.selectedPaths).isEmpty()
    }

    @Test
    fun `toggleSelectionMode entering then exiting clears all selections`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.toggleSelectionMode()
        vm.toggleSelection("file.txt")

        vm.toggleSelectionMode()

        assertThat(vm.state.value.selectionMode).isFalse()
        assertThat(vm.state.value.selectedPaths).isEmpty()
    }

    // -------------------------------------------------------------------------
    // Back behavior — up()
    // -------------------------------------------------------------------------

    @Test
    fun `up is a no-op when already at root`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("remote")
        advanceUntilIdle()
        assertThat(vm.state.value.path).isEmpty()

        vm.up()
        advanceUntilIdle()

        coVerify(exactly = 1) { engine.listDir(any(), any()) }
    }

    @Test
    fun `up navigates to parent directory from a nested path`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.open(dir("reports", "docs/reports"))
        advanceUntilIdle()

        vm.up()
        advanceUntilIdle()

        assertThat(vm.state.value.path).isEqualTo("docs")
    }

    @Test
    fun `up navigates to root from a single-level path`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.open(dir("docs", "docs"))
        advanceUntilIdle()

        vm.up()
        advanceUntilIdle()

        assertThat(vm.state.value.path).isEmpty()
    }

    @Test
    fun `up clears search query and exits selection mode`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.open(dir("sub", "sub"))
        advanceUntilIdle()
        vm.setSearchQuery("query")
        vm.enterSelectionMode("sub/file.txt")

        vm.up()
        advanceUntilIdle()

        assertThat(vm.state.value.searchQuery).isEmpty()
        assertThat(vm.state.value.selectionMode).isFalse()
    }

    // -------------------------------------------------------------------------
    // open()
    // -------------------------------------------------------------------------

    @Test
    fun `open navigates into a directory and clears search and selection`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("remote")
        advanceUntilIdle()
        vm.setSearchQuery("doc")
        vm.enterSelectionMode("docs")

        vm.open(dir("docs", "docs"))
        advanceUntilIdle()

        assertThat(vm.state.value.path).isEqualTo("docs")
        assertThat(vm.state.value.searchQuery).isEmpty()
        assertThat(vm.state.value.selectionMode).isFalse()
    }

    @Test
    fun `open is a no-op for file entries`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("remote")
        advanceUntilIdle()

        vm.open(file("readme.txt", "readme.txt"))
        advanceUntilIdle()

        assertThat(vm.state.value.path).isEmpty()
        coVerify(exactly = 1) { engine.listDir(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // toggleSearch
    // -------------------------------------------------------------------------

    @Test
    fun `toggleSearch activates search when inactive`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()

        vm.toggleSearch()

        assertThat(vm.state.value.searchActive).isTrue()
    }

    @Test
    fun `toggleSearch deactivates search and clears query when active`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.toggleSearch()
        vm.setSearchQuery("keyword")

        vm.toggleSearch()

        assertThat(vm.state.value.searchActive).isFalse()
        assertThat(vm.state.value.searchQuery).isEmpty()
    }

    // -------------------------------------------------------------------------
    // retry
    // -------------------------------------------------------------------------

    @Test
    fun `retry reloads current path when a remote is selected`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("remote")
        advanceUntilIdle()

        vm.retry()
        advanceUntilIdle()

        coVerify(exactly = 2) { engine.listDir(any(), any()) }
    }

    @Test
    fun `retry is a no-op when no remote is selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()

        vm.retry()
        advanceUntilIdle()

        coVerify(exactly = 0) { engine.listDir(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    fun `load surfaces error message when engine throws`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } throws RuntimeException("connection refused")
        val vm = viewModel()

        vm.selectRemote("remote")
        advanceUntilIdle()

        assertThat(vm.state.value.error).isNotNull()
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun `load clears loading flag and error on success`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()

        vm.selectRemote("remote")
        advanceUntilIdle()

        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.error).isNull()
    }
}
