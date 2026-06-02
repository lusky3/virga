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
    fun setWatchdog_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setWatchdog(true)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setWatchdogEnabled(true) }
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

    // --- setDynamicColor ---

    @Test
    fun setDynamicColor_enabled_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setDynamicColor(true)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setDynamicColor(true) }
    }

    @Test
    fun setDynamicColor_disabled_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setDynamicColor(false)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setDynamicColor(false) }
    }

    // --- initial / default state ---

    @Test
    fun state_initialValue_matchesAppPreferencesDefaults() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertThat(vm.state.value.themeMode).isEqualTo(ThemeMode.SYSTEM)
        // Brand-first (BRAND §4.3): dynamic color defaults off.
        assertThat(vm.state.value.dynamicColor).isFalse()
        assertThat(vm.state.value.wifiOnlyByDefault).isTrue()
        assertThat(vm.state.value.requireChargingByDefault).isFalse()
        assertThat(vm.state.value.defaultBwLimitWifi).isNull()
        assertThat(vm.state.value.defaultBwLimitMetered).isEqualTo("1M")
        job.cancel()
    }

    @Test
    fun state_reflectsWifiAndChargingAndBandwidthFields() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }

        prefsFlow.value = AppPreferences(
            wifiOnlyByDefault = false,
            requireChargingByDefault = true,
            defaultBwLimitWifi = "10M",
            defaultBwLimitMetered = null,
        )
        advanceUntilIdle()

        assertThat(vm.state.value.wifiOnlyByDefault).isFalse()
        assertThat(vm.state.value.requireChargingByDefault).isTrue()
        assertThat(vm.state.value.defaultBwLimitWifi).isEqualTo("10M")
        assertThat(vm.state.value.defaultBwLimitMetered).isNull()
        job.cancel()
    }

    // --- setDefaultBwLimits edge cases ---

    @Test
    fun setDefaultBwLimits_bothNull_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setDefaultBwLimits(wifi = null, metered = null)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setDefaultBwLimits(wifi = null, metered = null) }
    }

    @Test
    fun setDefaultBwLimits_meteredNull_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setDefaultBwLimits(wifi = "5M", metered = null)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setDefaultBwLimits(wifi = "5M", metered = null) }
    }

    // --- ThemeMode variants ---

    @Test
    fun setThemeMode_dark_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setThemeMode(ThemeMode.DARK) }
    }

    @Test
    fun setThemeMode_system_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setThemeMode(ThemeMode.SYSTEM)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setThemeMode(ThemeMode.SYSTEM) }
    }
}
