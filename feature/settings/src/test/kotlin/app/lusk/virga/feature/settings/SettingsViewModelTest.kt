package app.lusk.virga.feature.settings

import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.datastore.ThemeMode
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val prefsFlow = MutableStateFlow(AppPreferences())
    private val repository: PreferencesRepository = mockk(relaxed = true) {
        every { preferences } returns prefsFlow
    }

    private fun viewModel() = SettingsViewModel(repository)

    @Test
    fun state_reflectsRepositoryPreferences() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }

        prefsFlow.value = AppPreferences(themeMode = ThemeMode.DARK, dynamicColor = false)
        advanceUntilIdle()

        assertThat(vm.state.value.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(vm.state.value.dynamicColor).isFalse()
        job.cancel()
    }

    @Test
    fun setThemeMode_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setThemeMode(ThemeMode.LIGHT)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setThemeMode(ThemeMode.LIGHT) }
    }

    @Test
    fun setWifiOnly_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setWifiOnly(false)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setWifiOnlyByDefault(false) }
    }

    @Test
    fun setRequireCharging_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setRequireCharging(true)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setRequireChargingByDefault(true) }
    }

    @Test
    fun setDefaultBwLimits_passesBothValuesThrough() = runTest(mainDispatcher.dispatcher) {
        viewModel().setDefaultBwLimits(wifi = "5M", metered = "500k")
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setDefaultBwLimits(wifi = "5M", metered = "500k") }
    }

    @Test
    fun setDefaultBwLimits_acceptsNullToClearLimit() = runTest(mainDispatcher.dispatcher) {
        viewModel().setDefaultBwLimits(wifi = null, metered = "1M")
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setDefaultBwLimits(wifi = null, metered = "1M") }
    }

    @Test
    fun multipleSetters_areApplied_inOrderTheyAreCalled() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        vm.setDynamicColor(false)
        vm.setThemeMode(ThemeMode.SYSTEM)
        vm.setWifiOnly(true)
        advanceUntilIdle()

        coVerifyOrder {
            repository.setDynamicColor(false)
            repository.setThemeMode(ThemeMode.SYSTEM)
            repository.setWifiOnlyByDefault(true)
        }
    }
}
