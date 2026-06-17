package app.lusk.virga.feature.settings

import app.lusk.virga.core.data.SyncHistoryRepository
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
    private val historyRepository: SyncHistoryRepository = mockk(relaxed = true) {
        every { monthlyMeteredBytes(any()) } returns kotlinx.coroutines.flow.flowOf(0L)
    }

    private fun viewModel() = SettingsViewModel(repository, historyRepository)

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
    fun setAppLock_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setAppLock(true)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setAppLockEnabled(true) }
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

    // --- B4: quiet hours setters ---

    @Test
    fun setQuietHoursEnabled_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setQuietHoursEnabled(true)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setQuietHoursEnabled(true) }
    }

    @Test
    fun setQuietHoursStart_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setQuietHoursStart(1320)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setQuietHoursStart(1320) }
    }

    @Test
    fun setQuietHoursEnd_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setQuietHoursEnd(360)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setQuietHoursEnd(360) }
    }

    @Test
    fun quietHoursEnabled_defaultsFalse() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertThat(vm.state.value.quietHoursEnabled).isFalse()
        job.cancel()
    }

    @Test
    fun quietHoursEnabled_trueReflectedInState() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        prefsFlow.value = AppPreferences(quietHoursEnabled = true, quietHoursStartMinutes = 1320, quietHoursEndMinutes = 360)
        advanceUntilIdle()

        assertThat(vm.state.value.quietHoursEnabled).isTrue()
        assertThat(vm.state.value.quietHoursStartMinutes).isEqualTo(1320)
        assertThat(vm.state.value.quietHoursEndMinutes).isEqualTo(360)
        job.cancel()
    }

    // --- D5: notifyOnFailureOnly ---

    @Test
    fun setNotifyOnFailureOnly_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setNotifyOnFailureOnly(true)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setNotifyOnFailureOnly(true) }
    }

    @Test
    fun notifyOnFailureOnly_defaultsFalse() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertThat(vm.state.value.notifyOnFailureOnly).isFalse()
        job.cancel()
    }

    @Test
    fun notifyOnFailureOnly_trueReflectedInState() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        prefsFlow.value = AppPreferences(notifyOnFailureOnly = true)
        advanceUntilIdle()

        assertThat(vm.state.value.notifyOnFailureOnly).isTrue()
        job.cancel()
    }

    // --- D4: metered cap setters ---

    @Test
    fun setMeteredCapEnabled_true_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setMeteredCapEnabled(true)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setMeteredCapEnabled(true) }
    }

    @Test
    fun setMeteredCapEnabled_false_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setMeteredCapEnabled(false)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setMeteredCapEnabled(false) }
    }

    @Test
    fun setMeteredCapMb_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setMeteredCapMb(500L)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setMeteredCapMb(500L) }
    }

    @Test
    fun meteredCapEnabled_defaultsFalse() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertThat(vm.state.value.meteredCapEnabled).isFalse()
        job.cancel()
    }

    @Test
    fun meteredCapEnabled_trueReflectedInState() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        prefsFlow.value = AppPreferences(meteredCapEnabled = true, meteredCapMb = 1000L)
        advanceUntilIdle()

        assertThat(vm.state.value.meteredCapEnabled).isTrue()
        assertThat(vm.state.value.meteredCapMb).isEqualTo(1000L)
        job.cancel()
    }

    // --- D4: monthlyMeteredBytes state ---

    @Test
    fun monthlyMeteredBytes_defaultsZero() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.monthlyMeteredBytes.collect {} }
        advanceUntilIdle()

        assertThat(vm.monthlyMeteredBytes.value).isEqualTo(0L)
        job.cancel()
    }

    // --- B3: event-driven trigger setter (consolidated setTrigger) ---

    @Test
    fun setTrigger_folderChange_true_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setTrigger(EventTriggerKind.FOLDER_CHANGE, true)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setTriggerOnFolderChange(true) }
    }

    @Test
    fun setTrigger_folderChange_false_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setTrigger(EventTriggerKind.FOLDER_CHANGE, false)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setTriggerOnFolderChange(false) }
    }

    @Test
    fun setTrigger_wifiConnect_true_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setTrigger(EventTriggerKind.WIFI_CONNECT, true)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setTriggerOnWifiConnect(true) }
    }

    @Test
    fun setTrigger_wifiConnect_false_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setTrigger(EventTriggerKind.WIFI_CONNECT, false)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setTriggerOnWifiConnect(false) }
    }

    @Test
    fun setTrigger_charge_true_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setTrigger(EventTriggerKind.CHARGE, true)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setTriggerOnCharge(true) }
    }

    @Test
    fun setTrigger_charge_false_delegatesToRepository() = runTest(mainDispatcher.dispatcher) {
        viewModel().setTrigger(EventTriggerKind.CHARGE, false)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.setTriggerOnCharge(false) }
    }

    @Test
    fun triggerOnFolderChange_defaultsFalse() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertThat(vm.state.value.triggerOnFolderChange).isFalse()
        job.cancel()
    }

    @Test
    fun triggerOnWifiConnect_defaultsFalse() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertThat(vm.state.value.triggerOnWifiConnect).isFalse()
        job.cancel()
    }

    @Test
    fun triggerOnCharge_defaultsFalse() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        assertThat(vm.state.value.triggerOnCharge).isFalse()
        job.cancel()
    }

    @Test
    fun triggerPrefs_trueReflectedInState() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val job = backgroundScope.launch { vm.state.collect {} }
        prefsFlow.value = AppPreferences(
            triggerOnFolderChange = true,
            triggerOnWifiConnect = true,
            triggerOnCharge = true,
        )
        advanceUntilIdle()

        assertThat(vm.state.value.triggerOnFolderChange).isTrue()
        assertThat(vm.state.value.triggerOnWifiConnect).isTrue()
        assertThat(vm.state.value.triggerOnCharge).isTrue()
        job.cancel()
    }

    @Test
    fun monthlyMeteredBytes_reflectsRepositoryFlow() = runTest(mainDispatcher.dispatcher) {
        val usageFlow = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val localHistoryRepo: SyncHistoryRepository = mockk(relaxed = true) {
            every { monthlyMeteredBytes(any()) } returns usageFlow
        }
        val localPrefsRepo: PreferencesRepository = mockk(relaxed = true) {
            every { preferences } returns prefsFlow
        }
        val vm = SettingsViewModel(localPrefsRepo, localHistoryRepo)
        val job = backgroundScope.launch { vm.monthlyMeteredBytes.collect {} }
        usageFlow.value = 52_428_800L // 50 MB
        advanceUntilIdle()

        assertThat(vm.monthlyMeteredBytes.value).isEqualTo(52_428_800L)
        job.cancel()
    }
}
