package app.lusk.virga.feature.remotes.oauth

import android.net.Uri
import app.lusk.virga.core.rclone.oauth.OAuthResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI-M3: tests the exported-activity security gate. [classifyOAuthRedirect] is the
 * decision point [OAuthRedirectActivity.handle] delegates to — a null return means
 * "don't emit, don't foreground", which is exactly the defense against an arbitrary
 * app sending this exported activity a bogus explicit intent (bypassing the manifest
 * intent-filters). Runs under Robolectric for a real android.net.Uri parser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OAuthRedirectActivityTest {

    @Test
    fun `valid App-Link redirect with code+state emits Success`() {
        val uri = Uri.parse("https://lusk.app/virga/oauth/callback?code=abc&state=xyz")
        val result = classifyOAuthRedirect(uri)
        assertThat(result).isInstanceOf(OAuthResult.Success::class.java)
        result as OAuthResult.Success
        assertThat(result.code).isEqualTo("abc")
        assertThat(result.state).isEqualTo("xyz")
    }

    @Test
    fun `valid Google reverse-client-id redirect emits Success`() {
        val uri = Uri.parse(
            "com.googleusercontent.apps.123-abc:/oauth2redirect?code=g-code&state=g-state",
        )
        val result = classifyOAuthRedirect(uri)
        assertThat(result).isInstanceOf(OAuthResult.Success::class.java)
        result as OAuthResult.Success
        assertThat(result.code).isEqualTo("g-code")
        assertThat(result.state).isEqualTo("g-state")
    }

    @Test
    fun `App-Link redirect carrying an error emits Error`() {
        val uri = Uri.parse("https://lusk.app/virga/oauth/callback?error=access_denied&state=s")
        val result = classifyOAuthRedirect(uri)
        assertThat(result).isInstanceOf(OAuthResult.Error::class.java)
        assertThat((result as OAuthResult.Error).message).isEqualTo("access_denied")
    }

    @Test
    fun `wrong path on the lusk_app host is rejected`() {
        // SEC: an exported activity can be hit by an explicit intent that bypasses the
        // path filter, so the gate must reject any path other than the callback.
        val uri = Uri.parse("https://lusk.app/anything-else?code=abc&state=xyz")
        assertThat(classifyOAuthRedirect(uri)).isNull()
    }

    @Test
    fun `wrong host is rejected`() {
        val uri = Uri.parse("https://evil.example/virga/oauth/callback?code=abc&state=xyz")
        assertThat(classifyOAuthRedirect(uri)).isNull()
    }

    @Test
    fun `wrong scheme is rejected`() {
        val uri = Uri.parse("virga://oauth/callback?code=abc&state=xyz")
        assertThat(classifyOAuthRedirect(uri)).isNull()
    }

    @Test
    fun `google scheme with wrong path is rejected`() {
        val uri = Uri.parse("com.googleusercontent.apps.123-abc:/wrong?code=abc&state=xyz")
        assertThat(classifyOAuthRedirect(uri)).isNull()
    }

    @Test
    fun `null data is rejected`() {
        assertThat(classifyOAuthRedirect(null)).isNull()
    }

    @Test
    fun `valid origin but malformed query (no code, no error) emits Error`() {
        val uri = Uri.parse("https://lusk.app/virga/oauth/callback?state=only")
        val result = classifyOAuthRedirect(uri)
        assertThat(result).isInstanceOf(OAuthResult.Error::class.java)
    }
}
