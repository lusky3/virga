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

    /**
     * Persists the onboarding-complete flag. On builds that ship crash reporting
     * (github/play — [BuildConfig.CRASH_REPORTING_AVAILABLE]) it also records the
     * user's first-launch consent choice ([crashReportingEnabled]); the default
     * follows the flavor ([BuildConfig.CRASH_REPORTING_DEFAULT_ON]: opt-out on
     * github, opt-in on play). On fdroid (no Sentry SDK) the consent is not written.
     */
    fun completeOnboarding(
        crashReportingEnabled: Boolean = app.lusk.virga.BuildConfig.CRASH_REPORTING_DEFAULT_ON,
    ) = viewModelScope.launch {
        if (app.lusk.virga.BuildConfig.CRASH_REPORTING_AVAILABLE) {
            preferences.setCrashReportingEnabled(crashReportingEnabled)
        }
        preferences.setOnboardingComplete(true)
    }
}
