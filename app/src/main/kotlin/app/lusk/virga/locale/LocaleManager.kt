package app.lusk.virga.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Applies a per-app locale override via AppCompat's per-app locale API
 * (androidx.appcompat 1.6+). The override is automatically persisted by
 * AppCompat across process death; we also store the tag in [AppPreferences]
 * so the Settings picker can read back the current selection.
 *
 * Call [apply] on the main thread:
 * - at startup (VirgaApplication / MainActivity) to restore the saved pref, and
 * - whenever the user picks a different language in Settings.
 */
object LocaleManager {

    /**
     * Applies [tag] as the per-app locale.
     * - A null or blank tag clears the override so the system locale is used.
     * - A valid BCP-47 tag (e.g. "en", "fr", "de") pins the app to that locale.
     */
    fun apply(tag: String?) {
        val localeList = if (tag.isNullOrBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    /**
     * Returns the BCP-47 tag of the currently active per-app locale override,
     * or null if the app is following the system locale.
     */
    fun currentTag(): String? {
        val list = AppCompatDelegate.getApplicationLocales()
        return if (list.isEmpty) null else list.toLanguageTags()
    }
}
