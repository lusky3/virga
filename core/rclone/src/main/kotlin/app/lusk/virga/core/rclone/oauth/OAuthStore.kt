package app.lusk.virga.core.rclone.oauth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome posted by [OAuthRedirectActivity] when the browser returns. */
sealed class OAuthResult {
    data class Success(val state: String, val code: String) : OAuthResult()
    data class Error(val state: String?, val message: String) : OAuthResult()
}

/**
 * Hands a pending OAuth auth from the ViewModel that started it to the
 * redirect activity that receives the code, and back. Singleton-scoped because
 * both sides run in the same process but in different Hilt entry points.
 *
 * SEC-M3: Uses AtomicReference with compare-and-swap to eliminate the
 * @Volatile write race between startPending and consume. A pending auth that
 * is not consumed within PENDING_TTL_MS (5 minutes) is rejected and cleared.
 */
@Singleton
class OAuthStore @Inject constructor() {

    /** Wrapper that pairs the auth with the wall-clock time it was registered. */
    private data class TimedAuth(
        val auth: OAuthTokenExchanger.PendingAuth,
        val startedAtMs: Long,
    )

    private val _pending = AtomicReference<TimedAuth?>(null)

    // StateFlow, not SharedFlow: the redirect activity often emits the result
    // while the app is backgrounded behind the Custom Tab and the collecting
    // ViewModel has been torn down. replay=0 SharedFlow would drop the event in
    // that window. StateFlow replays the latest value to a late/re-subscribing
    // collector so the result is never lost; the collector calls clearResult()
    // after handling it to prevent reprocessing.
    private val _results = MutableStateFlow<OAuthResult?>(null)
    val results: StateFlow<OAuthResult?> = _results.asStateFlow()

    fun startPending(auth: OAuthTokenExchanger.PendingAuth) {
        // CAS: replace whatever was pending (including null) with the new auth.
        // This is safe under concurrent access: at most one pending auth is live
        // at a time, and any stale entry is atomically replaced.
        _pending.set(TimedAuth(auth, System.currentTimeMillis()))
    }

    /**
     * Returns the pending auth iff [state] matches and the pending auth was
     * started within [PENDING_TTL_MS]. Atomically clears it on a match so it
     * is single-use. Returns null (and clears) if the auth has expired.
     */
    fun consume(state: String): OAuthTokenExchanger.PendingAuth? {
        while (true) {
            val timed = _pending.get() ?: return null
            // Reject expired entries regardless of state match.
            if (System.currentTimeMillis() - timed.startedAtMs > PENDING_TTL_MS) {
                _pending.compareAndSet(timed, null)
                return null
            }
            // Constant-time comparison to avoid leaking the expected state via timing.
            if (!MessageDigest.isEqual(
                    timed.auth.state.toByteArray(Charsets.UTF_8),
                    state.toByteArray(Charsets.UTF_8),
                )
            ) {
                return null
            }
            // CAS clear: only one caller wins the race; the other sees null next loop.
            if (_pending.compareAndSet(timed, null)) return timed.auth
            // Another thread won the CAS — re-read and retry (next iteration
            // will see null and return null, which is correct).
        }
    }

    fun emit(result: OAuthResult) {
        _results.value = result
    }

    /** Clears the last result once the ViewModel has handled it. */
    fun clearResult() {
        _results.value = null
    }

    fun clear() {
        _pending.set(null)
    }

    private companion object {
        /** Pending OAuth auths older than this are silently expired. */
        const val PENDING_TTL_MS = 5 * 60 * 1_000L // 5 minutes
    }
}
