package app.lusk.virga.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
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
    suspend fun setLastSeenChangelogVersionCode(code: Int) = edit { it[Keys.LAST_SEEN_CHANGELOG] = code }
    suspend fun setCrashReportingEnabled(enabled: Boolean) = edit { it[Keys.CRASH_REPORTING] = enabled }
    suspend fun setAppLockEnabled(enabled: Boolean) = edit { it[Keys.APP_LOCK] = enabled }
    suspend fun setQuietHoursEnabled(enabled: Boolean) = edit { it[Keys.QUIET_HOURS_ENABLED] = enabled }
    suspend fun setQuietHoursStart(minutes: Int) = edit { it[Keys.QUIET_HOURS_START] = minutes.coerceIn(0, 1439) }
    suspend fun setQuietHoursEnd(minutes: Int) = edit { it[Keys.QUIET_HOURS_END] = minutes.coerceIn(0, 1439) }
    suspend fun setRunRetentionDays(days: Int) =
        edit { it[Keys.RUN_RETENTION_DAYS] = normalizeRetention(days) }

    suspend fun setAppLanguageTag(tag: String?) = edit { prefs ->
        if (tag.isNullOrBlank()) prefs.remove(Keys.APP_LANGUAGE_TAG)
        else prefs[Keys.APP_LANGUAGE_TAG] = tag
    }

    suspend fun setNotifyOnFailureOnly(enabled: Boolean) = edit { it[Keys.NOTIFY_ON_FAILURE_ONLY] = enabled }

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
        lastSeenChangelogVersionCode = this[Keys.LAST_SEEN_CHANGELOG] ?: 0,
        crashReportingEnabled = this[Keys.CRASH_REPORTING] ?: false,
        appLockEnabled = this[Keys.APP_LOCK] ?: false,
        quietHoursEnabled = this[Keys.QUIET_HOURS_ENABLED] ?: false,
        quietHoursStartMinutes = this[Keys.QUIET_HOURS_START] ?: 0,
        quietHoursEndMinutes = this[Keys.QUIET_HOURS_END] ?: 0,
        runRetentionDays = normalizeRetention(this[Keys.RUN_RETENTION_DAYS] ?: 0),
        appLanguageTag = this[Keys.APP_LANGUAGE_TAG],
        notifyOnFailureOnly = this[Keys.NOTIFY_ON_FAILURE_ONLY] ?: false,
    )

    /** Clamp a stored/incoming retention value to a value the UI knows how to render
     *  (0 = keep forever). Guards against a stale/garbage pref crashing the picker. */
    private fun normalizeRetention(days: Int): Int =
        if (days in RETENTION_OPTIONS) days else 0

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
        val LAST_SEEN_CHANGELOG = intPreferencesKey("last_seen_changelog_version_code")
        val CRASH_REPORTING = booleanPreferencesKey("crash_reporting_enabled")
        val APP_LOCK = booleanPreferencesKey("app_lock_enabled")
        val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        val QUIET_HOURS_START = intPreferencesKey("quiet_hours_start_minutes")
        val QUIET_HOURS_END = intPreferencesKey("quiet_hours_end_minutes")
        val RUN_RETENTION_DAYS = intPreferencesKey("run_retention_days")
        val APP_LANGUAGE_TAG = stringPreferencesKey("app_language_tag")
        val NOTIFY_ON_FAILURE_ONLY = booleanPreferencesKey("notify_on_failure_only")
    }

    private companion object {
        /** Retention values the Settings picker can render (0 = keep forever). */
        val RETENTION_OPTIONS = setOf(0, 30, 90, 180, 365)
    }
}
