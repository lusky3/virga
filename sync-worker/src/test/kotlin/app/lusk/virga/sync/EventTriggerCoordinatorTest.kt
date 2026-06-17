package app.lusk.virga.sync

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for [EventTriggerCoordinator], grouped into two concerns:
 *
 * 1. **Pure predicates** ([shouldObserveTask], [isSafPath]) — no Android, no coroutines.
 * 2. **Lifecycle / state-transition** — uses Robolectric for the Android [Context] and
 *    [UnconfinedTestDispatcher] injected via the internal constructor so flows drain
 *    eagerly under [runTest]. Exercises:
 *      - `start()` idempotency (double-start is a no-op)
 *      - `stop()` idempotency (double-stop is a no-op)
 *      - `start() → stop() → start()` re-arms the scope (not dead after stop)
 *      - scope is null after stop (no leak)
 *      - unrelated pref change does NOT rebuild the folder-watcher set (H2)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class EventTriggerCoordinatorTest {

    // -------------------------------------------------------------------------
    // isSafPath
    // -------------------------------------------------------------------------

    @Test
    fun isSafPath_contentUri_returnsTrue() {
        assertThat(isSafPath("content://com.android.externalstorage.documents/tree/primary%3ASync"))
            .isTrue()
    }

    @Test
    fun isSafPath_contentUriWithoutAuthority_returnsTrue() {
        assertThat(isSafPath("content://")).isTrue()
    }

    @Test
    fun isSafPath_absoluteFilesystemPath_returnsFalse() {
        assertThat(isSafPath("/storage/emulated/0/Documents/Backup")).isFalse()
    }

    @Test
    fun isSafPath_emptyString_returnsFalse() {
        assertThat(isSafPath("")).isFalse()
    }

    @Test
    fun isSafPath_relativePathLookingLikeContent_returnsFalse() {
        assertThat(isSafPath("content_backup/folder")).isFalse()
    }

    // -------------------------------------------------------------------------
    // shouldObserveTask
    // -------------------------------------------------------------------------

    @Test
    fun shouldObserveTask_enabledNonSafPath_returnsTrue() {
        val task = minimalTask(enabled = true, sourcePath = "/sdcard/Sync")
        assertThat(shouldObserveTask(task)).isTrue()
    }

    @Test
    fun shouldObserveTask_disabledNonSafPath_returnsFalse() {
        val task = minimalTask(enabled = false, sourcePath = "/sdcard/Sync")
        assertThat(shouldObserveTask(task)).isFalse()
    }

    @Test
    fun shouldObserveTask_enabledSafPath_returnsFalse() {
        val task = minimalTask(
            enabled = true,
            sourcePath = "content://com.android.externalstorage.documents/tree/primary%3ASync",
        )
        assertThat(shouldObserveTask(task)).isFalse()
    }

    @Test
    fun shouldObserveTask_disabledSafPath_returnsFalse() {
        val task = minimalTask(
            enabled = false,
            sourcePath = "content://com.android.externalstorage.documents/tree/primary%3ASync",
        )
        assertThat(shouldObserveTask(task)).isFalse()
    }

    // -------------------------------------------------------------------------
    // Lifecycle / state-transition tests
    //
    // Strategy: inject UnconfinedTestDispatcher so the three collector coroutines
    // drain synchronously during runTest. Use MutableStateFlow fakes for prefs and
    // tasks so we control exactly when emissions happen. SyncScheduler is an
    // unverified mockk (calls are fire-and-forget; we care about state not calls here).
    // -------------------------------------------------------------------------

    private val prefsFlow = MutableStateFlow(AppPreferences())
    private val tasksFlow = MutableStateFlow<List<SyncTask>>(emptyList())

    private val fakePrefs: PreferencesRepository = mockk {
        every { preferences } returns prefsFlow
    }
    private val fakeTasks: SyncTaskRepository = mockk {
        every { tasks } returns tasksFlow
    }
    private val fakeScheduler: SyncScheduler = mockk(relaxed = true)

    private fun makeCoordinator(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        EventTriggerCoordinator(
            context = RuntimeEnvironment.getApplication(),
            scheduler = fakeScheduler,
            taskRepository = fakeTasks,
            preferencesRepository = fakePrefs,
            confinement = dispatcher,
        )

    @Test
    fun start_isIdempotent_secondCallIsNoOp() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val coordinator = makeCoordinator(dispatcher)

        coordinator.start()
        val scopeAfterFirst = coordinator.scope

        coordinator.start() // second call: must not replace scope
        val scopeAfterSecond = coordinator.scope

        assertThat(scopeAfterFirst).isNotNull()
        assertThat(scopeAfterSecond).isSameInstanceAs(scopeAfterFirst)

        coordinator.stop()
    }

    @Test
    fun stop_isIdempotent_doubleStopDoesNotThrow() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val coordinator = makeCoordinator(dispatcher)

        coordinator.start()
        coordinator.stop()
        coordinator.stop() // must be a no-op
    }

    @Test
    fun stop_nullsScope_noLeakAfterStop() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val coordinator = makeCoordinator(dispatcher)

        coordinator.start()
        assertThat(coordinator.scope).isNotNull()

        coordinator.stop()
        assertThat(coordinator.scope).isNull()
    }

    @Test
    fun startStopStart_reArmsScope_triggersAreNotDeadAfterCycle() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val coordinator = makeCoordinator(dispatcher)

        coordinator.start()
        val firstScope = coordinator.scope
        coordinator.stop()

        coordinator.start() // must create a fresh scope, not reuse cancelled one
        val secondScope = coordinator.scope

        assertThat(secondScope).isNotNull()
        assertThat(secondScope).isNotSameInstanceAs(firstScope)

        coordinator.stop()
    }

    @Test
    fun unrelatedPrefChange_doesNotRebuildFolderWatchers() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val coordinator = makeCoordinator(dispatcher)

        // Enable folder-change trigger and provide one observable task.
        prefsFlow.value = AppPreferences(triggerOnFolderChange = true)
        tasksFlow.value = listOf(minimalTask(enabled = true, sourcePath = "/sdcard/A"))

        coordinator.start()

        // Snapshot the current observer map entries (as task ids).
        val observerIdsBefore = coordinator.folderObserversSnapshot()

        // Emit an unrelated pref change (e.g. dynamicColor toggle).
        prefsFlow.value = prefsFlow.value.copy(dynamicColor = true)

        // distinctUntilChanged on the folder-relevant projection means the
        // folder-watcher set must NOT have been torn down and rebuilt.
        val observerIdsAfter = coordinator.folderObserversSnapshot()

        assertThat(observerIdsAfter).isEqualTo(observerIdsBefore)

        coordinator.stop()
    }

    /**
     * P1 regression guard: verify that folder observers are cleared after [stop].
     *
     * Uses [StandardTestDispatcher] (not [UnconfinedTestDispatcher]) so that
     * cancellation and the watcher `finally` blocks are exercised in a way that
     * mirrors production. [advanceUntilIdle] drains all pending coroutines.
     */
    @Test
    fun stop_teardownRunsViaFinally_foldersObserversEmptyAfterStop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = makeCoordinator(dispatcher)

        prefsFlow.value = AppPreferences(triggerOnFolderChange = true)
        tasksFlow.value = listOf(minimalTask(enabled = true, sourcePath = "/sdcard/A"))

        coordinator.start()
        advanceUntilIdle() // drain collect + watch registration

        assertThat(coordinator.folderObserversSnapshot()).isNotEmpty()

        coordinator.stop()
        advanceUntilIdle() // let finally blocks run

        assertThat(coordinator.folderObserversSnapshot()).isEmpty()
    }

    /**
     * P1 regression guard: a stop → start cycle must NOT double-register watchers.
     * Observer count after restart must equal the number of tasks, not 2×.
     */
    @Test
    fun stopStart_doesNotDoubleRegisterFolderObservers() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = makeCoordinator(dispatcher)

        prefsFlow.value = AppPreferences(triggerOnFolderChange = true)
        tasksFlow.value = listOf(
            minimalTask(enabled = true, sourcePath = "/sdcard/A"),
            minimalTask(enabled = true, sourcePath = "/sdcard/B").copy(id = 2L),
        )

        coordinator.start()
        advanceUntilIdle()
        coordinator.stop()
        advanceUntilIdle()

        coordinator.start()
        advanceUntilIdle()

        assertThat(coordinator.folderObserversSnapshot()).hasSize(2)

        coordinator.stop()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun minimalTask(enabled: Boolean, sourcePath: String) = SyncTask(
        id = 1L,
        name = "test",
        sourcePath = sourcePath,
        remoteName = "remote",
        remotePath = "/",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = 60,
        enabled = enabled,
    )
}

// -------------------------------------------------------------------------
// Test-only accessors — same package, so they see internal members.
// -------------------------------------------------------------------------

/** Exposes the private [EventTriggerCoordinator.scope] for lifecycle assertions. */
internal val EventTriggerCoordinator.scope: kotlinx.coroutines.CoroutineScope?
    get() {
        val f = EventTriggerCoordinator::class.java.getDeclaredField("scope")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(this) as? kotlinx.coroutines.CoroutineScope
    }

/** Exposes [FolderWatchSet]'s observer key-set via the coordinator for H2 assertions. */
internal fun EventTriggerCoordinator.folderObserversSnapshot(): Set<Long> {
    val f = EventTriggerCoordinator::class.java.getDeclaredField("folderWatchSet")
    f.isAccessible = true
    return (f.get(this) as FolderWatchSet).observerIds()
}
