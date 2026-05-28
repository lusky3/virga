package app.lusk.virga.core.datastore

/** User-facing theme preference. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Snapshot of all app preferences, exposed as a Flow by [PreferencesRepository]. */
data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Global default; individual sync tasks may override. */
    val wifiOnlyByDefault: Boolean = true,
    val requireChargingByDefault: Boolean = false,
    val defaultBwLimitWifi: String? = null,
    val defaultBwLimitMetered: String? = "1M",
    val onboardingComplete: Boolean = false,
    /** BYO OAuth client ids keyed by provider, when the user overrides defaults. */
    val byoOAuthClientIds: Map<String, String> = emptyMap(),
)
