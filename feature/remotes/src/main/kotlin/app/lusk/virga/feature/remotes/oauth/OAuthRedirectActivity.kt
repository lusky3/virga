package app.lusk.virga.feature.remotes.oauth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.lusk.virga.core.rclone.oauth.OAuthResult
import app.lusk.virga.core.rclone.oauth.OAuthStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives the OAuth redirect from Custom Tabs — the verified App Link
 * `https://lusk.app/virga/oauth/callback?code=…&state=…` (or Google's
 * reverse-client-id scheme) — posts the outcome to [OAuthStore], and finishes
 * itself. The activity is `singleTask` with `noHistory=true` and a No-Display
 * theme so it never shows UI.
 */
@AndroidEntryPoint
class OAuthRedirectActivity : ComponentActivity() {

    @Inject lateinit var store: OAuthStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handle(intent)) returnToApp()
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handle(intent)) returnToApp()
        finish()
    }

    /**
     * Bring the app's main task back to the foreground. Custom Tabs sits on top
     * of our task, so without this the user is left looking at the browser
     * (often the provider's home page) after the redirect. CLEAR_TOP + SINGLE_TOP
     * reuses the existing MainActivity rather than creating a new one. We resolve
     * the launch intent via the package manager so this module needn't depend on
     * the app module's MainActivity.
     */
    private fun returnToApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (launch != null) startActivity(launch)
    }

    /**
     * Processes a redirect, returning true only if it was a genuine OAuth redirect
     * origin (and was emitted to [OAuthStore]). Returns false for a fabricated /
     * unexpected intent so the caller does NOT bring the app to the foreground — an
     * arbitrary app shouldn't be able to foreground Virga (or cancel an in-flight
     * authorization) by sending this exported activity a bogus intent.
     */
    private fun handle(intent: Intent?): Boolean {
        val result = classifyOAuthRedirect(intent?.data) ?: return false
        store.emit(result)
        return true
    }
}

/**
 * Maps a redirect [uri] to the [OAuthResult] it should emit, or null when the URI
 * is not one of Virga's real OAuth redirect origins (so the caller emits nothing
 * and does NOT foreground the app). Scheme, host AND path must all match the
 * manifest filters — the path check matters because the activity is exported and
 * another app can send an explicit intent that bypasses the intent-filters; without
 * it a crafted `https://lusk.app/anything` would be accepted.
 *
 * Pulled out as a pure top-level function so the exported-activity security gate is
 * unit-testable without driving the Activity lifecycle.
 */
internal fun classifyOAuthRedirect(uri: Uri?): OAuthResult? {
    if (uri == null || !isExpectedRedirect(uri)) return null
    val state = uri.getQueryParameter("state")
    val code = uri.getQueryParameter("code")
    val error = uri.getQueryParameter("error")
    return when {
        error != null -> OAuthResult.Error(state, message = error)
        code != null && state != null -> OAuthResult.Success(state = state, code = code)
        else -> OAuthResult.Error(state, message = "Malformed OAuth redirect")
    }
}

private fun isExpectedRedirect(uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase()
    return (scheme == "https" && uri.host == "lusk.app" && uri.path == "/virga/oauth/callback") ||
        (scheme?.startsWith("com.googleusercontent.apps.") == true && uri.path == "/oauth2redirect")
}
