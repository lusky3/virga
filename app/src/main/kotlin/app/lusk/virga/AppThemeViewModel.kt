package app.lusk.virga

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Tiny VM scoped to MainActivity that streams the theme-affecting subset of
 * [AppPreferences] to the root composition. Keeps the settings UI in sync with
 * what the app actually renders, rather than silently dropping the toggle.
 *
 * The default initial value (no dynamic color, system-followed dark) lets us
 * render the splash → root tree before DataStore has resolved.
 */
@HiltViewModel
class AppThemeViewModel @Inject constructor(
    preferences: PreferencesRepository,
) : ViewModel() {
    val themePrefs: StateFlow<AppPreferences> = preferences.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())
}
