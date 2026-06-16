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
 * Tests for [FileBrowserViewModel] covering:
 * - [FileBrowserViewModel.refresh]: toggles [FileBrowserUiState.isRefreshing] (not [loading])
 *   and re-invokes [FileBrowserRepository.list].
 * - [FileBrowserViewModel.showProperties] / [FileBrowserViewModel.dismissProperties]: set/clear
 *   [FileBrowserUiState.propertiesItem].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserRefreshPropertiesViewModelTest {

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
        FileItem(name = name, path = path, isDir = false, size = 1024L, modTimeEpochMs = 1_609_459_200_000L)

    private fun dir(name: String, path: String = name) =
        FileItem(name = name, path = path, isDir = true, size = 0L, modTimeEpochMs = null)

    // -------------------------------------------------------------------------
    // refresh() — isRefreshing vs loading
    // -------------------------------------------------------------------------

    @Test
    fun `refresh re-lists the current path`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        // selectRemote triggers one list, refresh triggers a second.
        coVerify(exactly = 2) { engine.listDir(any(), any()) }
    }

    @Test
    fun `refresh does not set loading true`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        // After selectRemote is stuck (awaitCancellation), cancel via re-select.
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.refresh()
        // Kick off but don't let it finish: replace stub with a suspending one.
        coEvery { engine.listDir(any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        // Re-call refresh to start the in-flight one:
        vm.refresh()
        // The refresh is now in-flight.
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun `refresh sets isRefreshing true then clears it on success`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns listOf(file("notes.txt"))
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.state.value.isRefreshing).isFalse()
        assertThat(vm.state.value.rawEntries).hasSize(1)
    }

    @Test
    fun `refresh surfaces VirgaError and clears isRefreshing`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        coEvery { engine.listDir(any(), any()) } throws VirgaError.Rclone(message = "network error")
        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.state.value.isRefreshing).isFalse()
        assertThat(vm.state.value.error).isNotNull()
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun `refresh surfaces generic exception and clears isRefreshing`() = runTest(mainDispatcher.dispatcher) {
        coEvery { engine.listDir(any(), any()) } returns emptyList()
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()

        coEvery { engine.listDir(any(), any()) } throws RuntimeException("boom")
        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.state.value.isRefreshing).isFalse()
        assertThat(vm.state.value.error).isNotNull()
        assertThat(vm.state.value.loading).isFalse()
    }

    @Test
    fun `refresh cancelling an in-flight load never shows both spinners`() = runTest(mainDispatcher.dispatcher) {
        // Initial load gets stuck so loading=true with the load coroutine still alive.
        coEvery { engine.listDir(any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val vm = viewModel()
        vm.selectRemote("gdrive")
        advanceUntilIdle()
        assertThat(vm.state.value.loading).isTrue()

        // refresh() cancels that load and starts a refresh that also stays in-flight.
        vm.refresh()
        advanceUntilIdle()

        // The fix: starting the refresh clears the opposing `loading` flag atomically,
        // so the full-screen spinner and pull-to-refresh indicator are never both shown.
        assertThat(vm.state.value.loading && vm.state.value.isRefreshing).isFalse()
        assertThat(vm.state.value.loading).isFalse()
        assertThat(vm.state.value.isRefreshing).isTrue()
    }

    @Test
    fun `refresh is a no-op when no remote is selected`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()

        vm.refresh()
        advanceUntilIdle()

        coVerify(exactly = 0) { engine.listDir(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // showProperties / dismissProperties
    // -------------------------------------------------------------------------

    @Test
    fun `showProperties sets propertiesItem`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val item = file("report.pdf")

        vm.showProperties(item)

        assertThat(vm.state.value.propertiesItem).isEqualTo(item)
    }

    @Test
    fun `dismissProperties clears propertiesItem`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val item = file("report.pdf")
        vm.showProperties(item)

        vm.dismissProperties()

        assertThat(vm.state.value.propertiesItem).isNull()
    }

    @Test
    fun `showProperties replaces any previously shown item`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val first = file("a.txt")
        val second = file("b.txt")
        vm.showProperties(first)

        vm.showProperties(second)

        assertThat(vm.state.value.propertiesItem).isEqualTo(second)
    }

    @Test
    fun `dismissProperties is a no-op when no item is shown`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        assertThat(vm.state.value.propertiesItem).isNull()

        vm.dismissProperties()

        assertThat(vm.state.value.propertiesItem).isNull()
    }

    @Test
    fun `showProperties works for a directory item`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val folder = dir("Photos")

        vm.showProperties(folder)

        assertThat(vm.state.value.propertiesItem).isEqualTo(folder)
        assertThat(vm.state.value.propertiesItem?.isDir).isTrue()
    }
}
