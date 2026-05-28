package app.lusk.virga.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.datastore.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Gate state for the launcher Activity: `null` while we are still loading the
 * persisted flag, then `true`/`false`. The activity stays on the splash theme
 * for `null` so we don't flash the wrong UI.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
) : ViewModel() {

    val onboardingComplete: StateFlow<Boolean?> =
        preferences.preferences
            .map { it.onboardingComplete }
            .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = null)

    fun completeOnboarding() = viewModelScope.launch {
        preferences.setOnboardingComplete(true)
    }
}
