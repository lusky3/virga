package app.lusk.virga.widget

import app.cash.turbine.test
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.SyncTaskRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TaskWidgetConfigViewModel].
 *
 * The VM wraps [SyncTaskRepository.tasks] in a [stateIn] with
 * [SharingStarted.WhileSubscribed(5_000)]. WhileSubscribed means the upstream
 * flow is only collected while there is at least one active subscriber. In a
 * test this requires an explicit collector (launched in a [backgroundScope] or
 * via Turbine's [test] block) to be in place before [advanceUntilIdle] is
 * called — otherwise the StateFlow remains at the initial empty-list value.
 *
 * This follows the established pattern in [HomeBannersViewModelTest]:
 * - [StandardTestDispatcher] as the coroutine dispatcher
 * - [Dispatchers.setMain] in [BeforeEach] / [resetMain] in [AfterEach]
 * - Turbine [test] block as the subscription vehicle
 * - [advanceUntilIdle] to drive emissions to completion
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskWidgetConfigViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val tasksFlow = MutableStateFlow<List<SyncTask>>(emptyList())
    private val repo: SyncTaskRepository = mockk(relaxed = true) {
        every { tasks } returns tasksFlow
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = TaskWidgetConfigViewModel(repo)

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun task(id: Long, name: String = "Task $id") = SyncTask(
        id = id,
        name = name,
        sourcePath = "/src",
        remoteName = "gdrive",
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
    )

    // ── initial state ─────────────────────────────────────────────────────────

    @Test
    fun `should expose an empty list when repository emits no tasks`() = runTest(dispatcher) {
        tasksFlow.value = emptyList()
        val viewModel = vm()

        viewModel.tasks.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── task list propagation ─────────────────────────────────────────────────

    @Test
    fun `should surface a single task emitted by the repository`() = runTest(dispatcher) {
        val single = task(id = 1L, name = "Photos Backup")
        tasksFlow.value = listOf(single)
        val viewModel = vm()

        viewModel.tasks.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem()).containsExactly(single)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `should surface all tasks when the repository emits multiple items`() = runTest(dispatcher) {
        val t1 = task(id = 1L)
        val t2 = task(id = 2L)
        val t3 = task(id = 3L)
        tasksFlow.value = listOf(t1, t2, t3)
        val viewModel = vm()

        viewModel.tasks.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem()).containsExactly(t1, t2, t3).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── reactivity: upstream flow updates ────────────────────────────────────

    @Test
    fun `should reflect new tasks added to the repository after the VM is created`() = runTest(dispatcher) {
        tasksFlow.value = emptyList()
        val viewModel = vm()
        val added = task(id = 5L, name = "Documents")

        viewModel.tasks.test {
            // drain initial empty-list emission
            advanceUntilIdle()
            skipItems(1)

            // upstream emits a new list
            tasksFlow.value = listOf(added)
            advanceUntilIdle()

            assertThat(awaitItem()).containsExactly(added)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `should reflect a task list that shrinks after the VM is created`() = runTest(dispatcher) {
        val t1 = task(id = 1L)
        val t2 = task(id = 2L)
        tasksFlow.value = listOf(t1, t2)
        val viewModel = vm()

        viewModel.tasks.test {
            // drain the initial emission (the 2-item list is already in the flow)
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertThat(initial).containsExactly(t1, t2).inOrder()

            // simulate a task being deleted
            tasksFlow.value = listOf(t1)
            advanceUntilIdle()

            assertThat(awaitItem()).containsExactly(t1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── tasks StateFlow type ──────────────────────────────────────────────────

    @Test
    fun `should hold an empty list as its initial value before any subscriber attaches`() {
        // No subscription — the StateFlow's current value must be the initialValue
        // supplied to stateIn(..., emptyList()).
        val viewModel = vm()
        assertThat(viewModel.tasks.value).isEmpty()
    }
}
