package app.lusk.virga.core.rclone.oauth

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 */
@Singleton
class OAuthStore @Inject constructor() {

    @Volatile
    private var pending: OAuthTokenExchanger.PendingAuth? = null

    private val _results = MutableSharedFlow<OAuthResult>(replay = 0, extraBufferCapacity = 1)
    val results: SharedFlow<OAuthResult> = _results.asSharedFlow()

    fun startPending(auth: OAuthTokenExchanger.PendingAuth) {
        pending = auth
    }

    /** Returns the pending auth iff the state matches; nulls it out so it's single-use. */
    fun consume(state: String): OAuthTokenExchanger.PendingAuth? {
        val p = pending ?: return null
        if (p.state != state) return null
        pending = null
        return p
    }

    fun emit(result: OAuthResult) {
        _results.tryEmit(result)
    }

    fun clear() {
        pending = null
    }
}
