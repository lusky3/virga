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
import javax.inject.Inject

/**
 * Manages the UI lock state for the opt-in app-lock feature.
 *
 * Lock semantics
 * --------------
 * [locked] is derived purely from two inputs, so there is no separate coroutine
 * racing to mutate it (which would risk a one-frame flash of unlocked content):
 * - `appLockEnabled` pref (null while the DataStore read is in flight), and
 * - an internal `unlocked` flag (false until the user authenticates).
 * `locked = appLockEnabled == true && !unlocked`. Therefore:
 * - feature off → always unlocked.
 * - cold start with the feature on → locked (unlocked starts false).
 * - after [markUnlocked] → unlocked until a re-lock fires.
 * - re-lock: on [ProcessLifecycleOwner] ON_START, if the app was backgrounded
 *   longer than [GRACE_PERIOD_MS], the unlocked flag is cleared.
 *
 * Availability fail-safe
 * ----------------------
 * App-lock is a UI-only convenience; rclone config is already encrypted at rest.
 * If app-lock is enabled but the device has no enrolled biometric or credential
 * (e.g. the user removed their PIN after enabling), MainActivity's prompt path
 * checks [BiometricGate.canAuthenticate] first and, when it returns false, calls
 * [markUnlocked] immediately so the user can never be permanently locked out.
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

    /** False until the user authenticates; cleared again on a grace-period re-lock. */
    private val _unlocked = MutableStateFlow(false)

    /**
     * Nullable: null = prefs not yet loaded (DataStore cold read in flight).
     * Once loaded, reflects [app.lusk.virga.core.datastore.AppPreferences.appLockEnabled].
     */
    private val _appLockEnabled: StateFlow<Boolean?> = preferences.preferences
        .map { it.appLockEnabled as Boolean? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Monotonic timestamp ([SystemClock.elapsedRealtime]) when the app last backgrounded. */
    private var backgroundedAt: Long? = null

    /**
     * True when the UI should show [LockScreen] instead of VirgaNavHost. Pure
     * derivation of the two inputs — while the pref is still loading (null), this is
     * false, but the splash is held by [resolved] until the pref resolves, so a
     * lock-enabled user never sees content first.
     */
    val locked: StateFlow<Boolean> = combine(_appLockEnabled, _unlocked) { enabled, unlocked ->
        enabled == true && !unlocked
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * True once the app-lock preference has loaded. The splash screen waits on this
     * (alongside onboarding status) so the first composed frame already reflects the
     * correct [locked] value.
     */
    val resolved: StateFlow<Boolean> = _appLockEnabled
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Re-locks (clears [_unlocked]) when the app returns to the foreground after more
     * than [GRACE_PERIOD_MS] in the background. Held as a field and removed in
     * [onCleared] so a recreated ViewModel isn't retained by the process-global
     * [ProcessLifecycleOwner] (the watchdog FGS can keep the process alive after the
     * Activity finishes).
     */
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            backgroundedAt = SystemClock.elapsedRealtime()
        }

        override fun onStart(owner: LifecycleOwner) {
            val since = backgroundedAt ?: return
            backgroundedAt = null
            if (SystemClock.elapsedRealtime() - since >= GRACE_PERIOD_MS) {
                _unlocked.value = false
            }
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        super.onCleared()
    }

    /** Call after a successful biometric/device-credential authentication. */
    fun markUnlocked() {
        _unlocked.value = true
        backgroundedAt = null
    }
}
