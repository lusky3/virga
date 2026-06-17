package app.lusk.virga.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.datastore.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val historyRepository: SyncHistoryRepository,
) : ViewModel() {

    val state: StateFlow<AppPreferences> = preferences.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    val monthlyMeteredBytes: StateFlow<Long> = historyRepository
        .monthlyMeteredBytes(currentMonthStartMs())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private fun currentMonthStartMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

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
    fun setQuietHoursEnabled(enabled: Boolean) =
        viewModelScope.launch { preferences.setQuietHoursEnabled(enabled) }
    fun setQuietHoursStart(minutes: Int) =
        viewModelScope.launch { preferences.setQuietHoursStart(minutes) }
    fun setQuietHoursEnd(minutes: Int) =
        viewModelScope.launch { preferences.setQuietHoursEnd(minutes) }
    fun setRunRetentionDays(days: Int) =
        viewModelScope.launch { preferences.setRunRetentionDays(days) }

    fun setAppLanguageTag(tag: String?) =
        viewModelScope.launch { preferences.setAppLanguageTag(tag) }

    fun setNotifyOnFailureOnly(enabled: Boolean) =
        viewModelScope.launch { preferences.setNotifyOnFailureOnly(enabled) }

    fun setMeteredCapEnabled(enabled: Boolean) =
        viewModelScope.launch { preferences.setMeteredCapEnabled(enabled) }

    fun setMeteredCapMb(mb: Long) =
        viewModelScope.launch { preferences.setMeteredCapMb(mb) }

    // B3: event-driven sync triggers
    fun setTriggerOnFolderChange(enabled: Boolean) =
        viewModelScope.launch { preferences.setTriggerOnFolderChange(enabled) }

    fun setTriggerOnWifiConnect(enabled: Boolean) =
        viewModelScope.launch { preferences.setTriggerOnWifiConnect(enabled) }

    fun setTriggerOnCharge(enabled: Boolean) =
        viewModelScope.launch { preferences.setTriggerOnCharge(enabled) }

    fun clearCache(context: Context, onComplete: (Boolean) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }.isSuccess
        withContext(Dispatchers.Main) { onComplete(ok) }
    }

    fun clearLogs(context: Context, onComplete: (Boolean) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching {
            context.filesDir.resolve("run_logs").listFiles()
                ?.filter { it.extension == "log" }
                ?.forEach { it.delete() }
        }.isSuccess
        withContext(Dispatchers.Main) { onComplete(ok) }
    }

    fun clearAppData(context: Context, onComplete: (Boolean) -> Unit) = viewModelScope.launch {
        // clearApplicationUserData() wipes all app data and restarts the process from scratch.
        // No explicit DataStore/Room clear is needed — the OS handles everything.
        // Added in API 19 (always available on supported Android versions).
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val success = am?.clearApplicationUserData() ?: false
        withContext(Dispatchers.Main) { onComplete(success) }
    }
}
