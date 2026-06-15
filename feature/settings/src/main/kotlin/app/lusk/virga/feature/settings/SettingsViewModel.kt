package app.lusk.virga.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.datastore.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
) : ViewModel() {

    val state: StateFlow<AppPreferences> = preferences.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferences.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { preferences.setDynamicColor(enabled) }
    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch { preferences.setWifiOnlyByDefault(enabled) }
    fun setRequireCharging(enabled: Boolean) = viewModelScope.launch { preferences.setRequireChargingByDefault(enabled) }
    fun setShowAdvancedOptions(enabled: Boolean) = viewModelScope.launch { preferences.setShowAdvancedOptions(enabled) }
    fun setWatchdog(enabled: Boolean) = viewModelScope.launch { preferences.setWatchdogEnabled(enabled) }
    fun setCrashReporting(enabled: Boolean) = viewModelScope.launch { preferences.setCrashReportingEnabled(enabled) }
    fun setAppLock(enabled: Boolean) = viewModelScope.launch { preferences.setAppLockEnabled(enabled) }
    fun setDefaultBwLimits(wifi: String?, metered: String?) =
        viewModelScope.launch { preferences.setDefaultBwLimits(wifi, metered) }
}
