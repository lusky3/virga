package app.lusk.virga.core.rclone.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) helpers. Mobile OAuth public clients ship without a client
 * secret; PKCE binds the code to the originating verifier so an intercepted
 * code is useless without it.
 */
object Pkce {
    /** Returns a 64-char URL-safe random `code_verifier`. */
    fun newVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    /** Returns the S256 code_challenge derived from [verifier]. */
    fun challenge(verifier: String): String {
        val sha = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(sha)
    }

    // java.util.Base64 is available on minSdk 26+; using it lets us run this
    // pure logic in JVM unit tests without Robolectric. URL-safe + no padding
    // matches what android.util.Base64 produced with URL_SAFE | NO_PADDING.
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private fun base64Url(bytes: ByteArray): String = encoder.encodeToString(bytes)
}
