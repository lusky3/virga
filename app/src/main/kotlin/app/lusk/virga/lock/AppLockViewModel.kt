package app.lusk.virga.lock

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.datastore.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manages the UI lock state for the opt-in app-lock feature.
 *
 * Lock semantics
 * --------------
 * - When appLockEnabled is false: [locked] is always false (feature off).
 * - Cold start with appLockEnabled true: [locked] starts true.
 * - After [markUnlocked]: [locked] is false until a re-lock condition fires.
 * - Re-lock: on [ProcessLifecycleOwner] ON_START, if the app was backgrounded for
 *   longer than [GRACE_PERIOD_MS], [locked] returns to true.
 *
 * Availability fail-safe
 * ----------------------
 * App-lock is a UI-only convenience; rclone config is already encrypted at rest.
 * If app-lock is enabled but the device has no enrolled biometric or credential
 * (e.g. the user removed their PIN after enabling), the lock screen prompt path
 * checks [BiometricGate.canAuthenticate] first and, when it returns false, calls
 * [markUnlocked] immediately. This prevents the user being permanently locked out.
 * See MainActivity for this fail-open logic.
 *
 * Worker/FGS isolation
 * --------------------
 * This ViewModel is ONLY consulted by the UI layer (MainActivity). The WorkManager
 * sync worker and the watchdog foreground service are NOT gated by [locked] — they
 * keep running regardless of the UI lock state.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    preferences: PreferencesRepository,
) : ViewModel() {

    companion object {
        /** Minimum background duration (ms) before the app re-locks on foreground. */
        const val GRACE_PERIOD_MS = 60_000L
    }

    private val _locked = MutableStateFlow(false)

    /**
     * Nullable: null = prefs not yet loaded (DataStore cold read in flight).
     * Once loaded, reflects [AppPreferences.appLockEnabled].
     */
    private val _appLockEnabled: StateFlow<Boolean?> = preferences.preferences
        .map { it.appLockEnabled as Boolean? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Monotonic timestamp ([SystemClock.elapsedRealtime]) when the app last went to background. */
    private var backgroundedAt: Long? = null

    /**
     * True when the UI should show [LockScreen] instead of [VirgaNavHost].
     *
     * Always false when appLockEnabled is false (feature off). When appLockEnabled
     * is true, reflects the internal [_locked] state driven by auth events and the
     * grace-period timer.
     */
    val locked: StateFlow<Boolean> = combine(_locked, _appLockEnabled) { lock, enabled ->
        // While the pref is still loading (null), propagate whatever _locked holds.
        if (enabled == null) lock else lock && enabled
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * True once the app-lock preference has loaded. The splash screen waits on this
     * (alongside onboarding status) so the first composed frame already reflects the
     * correct [locked] value — a lock-enabled user never sees a flash of unlocked
     * content on cold start before the pref read completes.
     */
    val resolved: StateFlow<Boolean> = _appLockEnabled
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Observe ProcessLifecycleOwner for background/foreground transitions.
        // Uses DefaultLifecycleObserver to keep each callback short and named.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                backgroundedAt = SystemClock.elapsedRealtime()
            }

            override fun onStart(owner: LifecycleOwner) {
                val since = backgroundedAt ?: return
                val elapsed = SystemClock.elapsedRealtime() - since
                if (elapsed >= GRACE_PERIOD_MS) {
                    _locked.value = true
                }
                backgroundedAt = null
            }
        })

        // React to pref changes: lock when the feature is enabled, unlock state
        // when it is disabled. Cold start with enabled=true → _locked becomes true.
        viewModelScope.launch {
            _appLockEnabled.collect { enabled ->
                when {
                    enabled == true && !_locked.value -> _locked.value = true
                    enabled == false -> _locked.value = false
                }
            }
        }
    }

    /** Call after a successful biometric/device-credential authentication. */
    fun markUnlocked() {
        _locked.value = false
        backgroundedAt = null
    }
}
