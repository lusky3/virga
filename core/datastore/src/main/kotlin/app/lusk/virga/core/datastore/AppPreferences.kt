package app.lusk.virga.core.datastore

/** User-facing theme preference. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Snapshot of all app preferences, exposed as a Flow by [PreferencesRepository]. */
data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Global default; individual sync tasks may override. */
    val wifiOnlyByDefault: Boolean = true,
    val requireChargingByDefault: Boolean = false,
    val defaultBwLimitWifi: String? = null,
    val defaultBwLimitMetered: String? = "1M",
    val onboardingComplete: Boolean = false,
    /** Reveal Tier 2/3 advanced options in the task editor (default off — beginners). */
    val showAdvancedOptions: Boolean = false,
    /**
     * Keep a persistent foreground "watchdog" service running so scheduled syncs
     * survive aggressive OEM background-killing. Default off — it costs an ongoing
     * notification and some battery; only power users on hostile ROMs need it.
     */
    val watchdogEnabled: Boolean = false,
)
