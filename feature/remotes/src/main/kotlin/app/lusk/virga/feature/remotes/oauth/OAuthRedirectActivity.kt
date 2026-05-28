package app.lusk.virga.feature.remotes.oauth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.lusk.virga.core.rclone.oauth.OAuthResult
import app.lusk.virga.core.rclone.oauth.OAuthStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives the `virga://oauth/callback?code=…&state=…` redirect from Custom
 * Tabs, posts the outcome to [OAuthStore], and finishes itself. The activity
 * is `singleTask` with `noHistory=true` and a No-Display theme so it never
 * shows UI.
 */
@AndroidEntryPoint
class OAuthRedirectActivity : ComponentActivity() {

    @Inject lateinit var store: OAuthStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: run {
            store.emit(OAuthResult.Error(state = null, message = "Empty OAuth redirect"))
            return
        }
        val state = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        when {
            error != null -> store.emit(OAuthResult.Error(state, message = error))
            code != null && state != null -> store.emit(OAuthResult.Success(state = state, code = code))
            else -> store.emit(OAuthResult.Error(state, message = "Malformed OAuth redirect"))
        }
    }
}
