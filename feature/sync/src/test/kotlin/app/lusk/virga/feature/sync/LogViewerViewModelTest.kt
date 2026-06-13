package app.lusk.virga.feature.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    @TempDir
    lateinit var tempDir: File

    private fun logFile(name: String, content: String): String {
        val f = File(tempDir, name)
        f.writeText(content)
        return f.absolutePath
    }

    // --- initial state -----------------------------------------------------

    @Test
    fun uiState_initialValue_isLoadingWithEmptyLines() {
        val vm = LogViewerViewModel()

        val state = vm.uiState.value
        assertThat(state.loading).isTrue()
        assertThat(state.visibleLines).isEmpty()
        assertThat(state.totalLines).isEqualTo(0)
        assertThat(state.query).isEmpty()
        assertThat(state.readFailed).isFalse()
    }

    // --- load reads file lines ---------------------------------------------

    @Test
    fun load_readsFileLines_andClearsLoading() = runTest(mainDispatcher.dispatcher) {
        val vm = LogViewerViewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val path = logFile("run.log", "line one\nline two\nline three")

        vm.load(path)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.loading).isFalse()
        assertThat(state.visibleLines).containsExactly("line one", "line two", "line three").inOrder()
        assertThat(state.totalLines).isEqualTo(3)
        assertThat(state.readFailed).isFalse()
        job.cancel()
    }

    @Test
    fun load_trimsTrailingNewline_doesNotProduceEmptyTrailingLine() = runTest(mainDispatcher.dispatcher) {
        val vm = LogViewerViewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val path = logFile("run.log", "alpha\nbeta\n")

        vm.load(path)
        advanceUntilIdle()

        // The trailing '\n' must not yield a 3rd empty line.
        assertThat(vm.uiState.value.visibleLines).containsExactly("alpha", "beta").inOrder()
        assertThat(vm.uiState.value.totalLines).isEqualTo(2)
        job.cancel()
    }

    // --- empty file vs read failure ----------------------------------------

    @Test
    fun load_emptyFile_loadsWithNoLines_andReadFailedFalse() = runTest(mainDispatcher.dispatcher) {
        val vm = LogViewerViewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val path = logFile("empty.log", "")

        vm.load(path)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.loading).isFalse()
        assertThat(state.visibleLines).isEmpty()
        assertThat(state.totalLines).isEqualTo(0)
        // A successfully-read empty file is distinct from a read failure.
        assertThat(state.readFailed).isFalse()
        job.cancel()
    }

    @Test
    fun load_missingFile_setsReadFailed_andClearsLoading() = runTest(mainDispatcher.dispatcher) {
        val vm = LogViewerViewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val path = File(tempDir, "does-not-exist.log").absolutePath

        vm.load(path)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.loading).isFalse()
        assertThat(state.readFailed).isTrue()
        assertThat(state.visibleLines).isEmpty()
        job.cancel()
    }

    // --- idempotency: second load is ignored -------------------------------

    @Test
    fun load_calledTwice_ignoresSecondPath() = runTest(mainDispatcher.dispatcher) {
        val vm = LogViewerViewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val first = logFile("first.log", "first content")
        val second = logFile("second.log", "second content\nmore")

        vm.load(first)
        advanceUntilIdle()
        vm.load(second)
        advanceUntilIdle()

        // Still showing the first file's contents.
        assertThat(vm.uiState.value.visibleLines).containsExactly("first content")
        job.cancel()
    }

    // --- live query filtering ----------------------------------------------

    @Test
    fun setQuery_filtersLinesCaseInsensitively() = runTest(mainDispatcher.dispatcher) {
        val vm = LogViewerViewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val path = logFile("run.log", "INFO start\nERROR boom\ninfo done\nWARN slow")

        vm.load(path)
        advanceUntilIdle()
        vm.setQuery("info")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.query).isEqualTo("info")
        assertThat(state.visibleLines).containsExactly("INFO start", "info done").inOrder()
        // totalLines remains the unfiltered count.
        assertThat(state.totalLines).isEqualTo(4)
        job.cancel()
    }

    @Test
    fun setQuery_blank_showsAllLines() = runTest(mainDispatcher.dispatcher) {
        val vm = LogViewerViewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val path = logFile("run.log", "a\nb\nc")

        vm.load(path)
        advanceUntilIdle()
        vm.setQuery("b")
        advanceUntilIdle()
        assertThat(vm.uiState.value.visibleLines).containsExactly("b")

        vm.setQuery("   ")
        advanceUntilIdle()

        // Blank/whitespace query is treated as "no filter".
        assertThat(vm.uiState.value.visibleLines).containsExactly("a", "b", "c").inOrder()
        job.cancel()
    }

    @Test
    fun setQuery_noMatch_yieldsEmptyVisibleLines() = runTest(mainDispatcher.dispatcher) {
        val vm = LogViewerViewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val path = logFile("run.log", "alpha\nbeta")

        vm.load(path)
        advanceUntilIdle()
        vm.setQuery("zzz")
        advanceUntilIdle()

        assertThat(vm.uiState.value.visibleLines).isEmpty()
        assertThat(vm.uiState.value.totalLines).isEqualTo(2)
        job.cancel()
    }

    // --- rawText (Share action) --------------------------------------------

    @Test
    fun rawText_returnsFullJoinedText_regardlessOfQuery() = runTest(mainDispatcher.dispatcher) {
        val vm = LogViewerViewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        val path = logFile("run.log", "one\ntwo\nthree")

        vm.load(path)
        advanceUntilIdle()
        vm.setQuery("two") // filters the view but not the raw share text
        advanceUntilIdle()

        assertThat(vm.rawText()).isEqualTo("one\ntwo\nthree")
        job.cancel()
    }

    @Test
    fun rawText_isEmptyBeforeLoad() {
        val vm = LogViewerViewModel()

        assertThat(vm.rawText()).isEmpty()
    }
}
