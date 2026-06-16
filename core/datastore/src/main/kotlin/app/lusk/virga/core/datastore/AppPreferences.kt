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
    /**
     * The version code of the release whose changelog was last shown to the user.
     * When BuildConfig.VERSION_CODE > this value the changelog banner appears.
     */
    val lastSeenChangelogVersionCode: Int = 0,
    /**
     * Opt-in crash reporting (Sentry). Default OFF — Virga ships "no tracking" by
     * default, so the sync engine never sends anything off-device unless the user
     * explicitly enables this. Reacted to in VirgaApplication (init/close Sentry).
     */
    val crashReportingEnabled: Boolean = false,
    /**
     * Opt-in biometric/device-credential app lock. Default OFF. When enabled the app
     * UI is gated behind a biometric or PIN/pattern/password prompt. The sync worker
     * and foreground service are NEVER gated — they keep running while the UI is locked.
     */
    val appLockEnabled: Boolean = false,
    // B4: global quiet hours (blackout window) ----------------------------------
    /**
     * When true, SCHEDULED syncs are suppressed during the window
     * [[quietHoursStartMinutes], [quietHoursEndMinutes]). Manual "Sync now" always
     * bypasses quiet hours — user intent. Periodic (WorkManager) intervals are
     * suppressed at the worker level; calendar runs are shifted past the window by
     * the scheduler.
     */
    val quietHoursEnabled: Boolean = false,
    /**
     * Start of the quiet-hours window, in minutes-of-day (0..1439, default 0 = 00:00).
     * If quietHoursStartMinutes > quietHoursEndMinutes the window wraps midnight.
     */
    val quietHoursStartMinutes: Int = 0,
    /**
     * End of the quiet-hours window, in minutes-of-day (0..1439, default 0 = 00:00).
     * Equal to quietHoursStartMinutes means disabled / zero-width window.
     */
    val quietHoursEndMinutes: Int = 0,
)
