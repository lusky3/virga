package app.lusk.virga.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed wrapper over a Preferences [DataStore]. Reads expose a [Flow] of the
 * full [AppPreferences] snapshot; writes mutate single keys.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<AppPreferences> = dataStore.data
        // A corrupt/unreadable prefs file would otherwise throw on the read and
        // crash the launch path; degrade to defaults instead.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toAppPreferences() }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setWifiOnlyByDefault(enabled: Boolean) = edit { it[Keys.WIFI_ONLY] = enabled }
    suspend fun setRequireChargingByDefault(enabled: Boolean) = edit { it[Keys.REQUIRE_CHARGING] = enabled }
    suspend fun setOnboardingComplete(complete: Boolean) = edit { it[Keys.ONBOARDING] = complete }
    suspend fun setShowAdvancedOptions(enabled: Boolean) = edit { it[Keys.SHOW_ADVANCED] = enabled }
    suspend fun setWatchdogEnabled(enabled: Boolean) = edit { it[Keys.WATCHDOG] = enabled }

    suspend fun setDefaultBwLimits(wifi: String?, metered: String?) = edit { prefs ->
        if (wifi.isNullOrBlank()) prefs.remove(Keys.BW_WIFI) else prefs[Keys.BW_WIFI] = wifi
        if (metered.isNullOrBlank()) prefs.remove(Keys.BW_METERED) else prefs[Keys.BW_METERED] = metered
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun Preferences.toAppPreferences(): AppPreferences = AppPreferences(
        themeMode = this[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM,
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: false,
        wifiOnlyByDefault = this[Keys.WIFI_ONLY] ?: true,
        requireChargingByDefault = this[Keys.REQUIRE_CHARGING] ?: false,
        defaultBwLimitWifi = this[Keys.BW_WIFI],
        defaultBwLimitMetered = this[Keys.BW_METERED] ?: "1M",
        onboardingComplete = this[Keys.ONBOARDING] ?: false,
        showAdvancedOptions = this[Keys.SHOW_ADVANCED] ?: false,
        watchdogEnabled = this[Keys.WATCHDOG] ?: false,
    )

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_default")
        val REQUIRE_CHARGING = booleanPreferencesKey("require_charging_default")
        val BW_WIFI = stringPreferencesKey("bw_limit_wifi")
        val BW_METERED = stringPreferencesKey("bw_limit_metered")
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val SHOW_ADVANCED = booleanPreferencesKey("show_advanced_options")
        val WATCHDOG = booleanPreferencesKey("watchdog_enabled")
    }
}
