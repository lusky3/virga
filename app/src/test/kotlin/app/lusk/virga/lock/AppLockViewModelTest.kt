package app.lusk.virga.lock

import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AppLockViewModel]'s pref-driven lock semantics and [markUnlocked].
 *
 * # Why Robolectric?
 * [AppLockViewModel.init] calls [androidx.lifecycle.ProcessLifecycleOwner.get] — a
 * process-global that requires an Android runtime. There is no injection seam to
 * substitute it in plain JUnit5. Running under Robolectric provides the runtime so
 * the ViewModel can be instantiated without crashing.
 *
 * # What is NOT covered here
 * The grace-period re-lock path (onStop records a timestamp; onStart re-locks when
 * the elapsed gap >= [AppLockViewModel.GRACE_PERIOD_MS]) cannot be exercised from a
 * plain unit test because driving [ProcessLifecycleOwner] transitions requires the
 * full Android lifecycle machinery or an instrumented test. That path needs either:
 *   - An injectable `clock` / `lifecycleOwner` seam added to [AppLockViewModel], or
 *   - An instrumented test using [ActivityScenario] / lifecycle test rules.
 * Until such a seam exists, the grace-period branch is deferred to a later review.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val prefsFlow = MutableStateFlow(AppPreferences())
    private val repository: PreferencesRepository = mockk(relaxed = true) {
        every { preferences } returns prefsFlow
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AppLockViewModel(repository)

    // --- pref disabled (feature off) ---

    @Test
    fun `locked is false when appLockEnabled pref is false`() = runTest(dispatcher) {
        prefsFlow.value = AppPreferences(appLockEnabled = false)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.locked.collect {} }

        advanceUntilIdle()

        assertThat(vm.locked.value).isFalse()
        job.cancel()
    }

    @Test
    fun `locked stays false after markUnlocked when pref is false`() = runTest(dispatcher) {
        prefsFlow.value = AppPreferences(appLockEnabled = false)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.locked.collect {} }

        advanceUntilIdle()
        vm.markUnlocked()
        advanceUntilIdle()

        assertThat(vm.locked.value).isFalse()
        job.cancel()
    }

    // --- pref enabled — cold-start ---

    @Test
    fun `locked starts true on cold start when appLockEnabled pref is true`() = runTest(dispatcher) {
        prefsFlow.value = AppPreferences(appLockEnabled = true)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.locked.collect {} }

        advanceUntilIdle()

        assertThat(vm.locked.value).isTrue()
        job.cancel()
    }

    // --- markUnlocked ---

    @Test
    fun `locked becomes false after markUnlocked when pref is true`() = runTest(dispatcher) {
        prefsFlow.value = AppPreferences(appLockEnabled = true)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.locked.collect {} }

        advanceUntilIdle()
        assertThat(vm.locked.value).isTrue()

        vm.markUnlocked()
        advanceUntilIdle()

        assertThat(vm.locked.value).isFalse()
        job.cancel()
    }

    // --- pref toggle transitions ---

    @Test
    fun `toggling pref false to true re-locks`() = runTest(dispatcher) {
        prefsFlow.value = AppPreferences(appLockEnabled = false)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.locked.collect {} }

        advanceUntilIdle()
        assertThat(vm.locked.value).isFalse()

        prefsFlow.value = AppPreferences(appLockEnabled = true)
        advanceUntilIdle()

        assertThat(vm.locked.value).isTrue()
        job.cancel()
    }

    @Test
    fun `toggling pref true to false unlocks`() = runTest(dispatcher) {
        prefsFlow.value = AppPreferences(appLockEnabled = true)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.locked.collect {} }

        advanceUntilIdle()
        assertThat(vm.locked.value).isTrue()

        prefsFlow.value = AppPreferences(appLockEnabled = false)
        advanceUntilIdle()

        assertThat(vm.locked.value).isFalse()
        job.cancel()
    }

    // --- resolved (splash gate: pref loaded) ---

    @Test
    fun `resolved becomes true once the pref has loaded`() = runTest(dispatcher) {
        prefsFlow.value = AppPreferences(appLockEnabled = true)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.resolved.collect {} }

        advanceUntilIdle()

        // The splash gates on this; once the DataStore read completes it flips true
        // so the first composed frame already reflects the correct lock state.
        assertThat(vm.resolved.value).isTrue()
        job.cancel()
    }
}
