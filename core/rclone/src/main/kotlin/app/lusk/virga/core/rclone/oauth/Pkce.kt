package app.lusk.virga.core.rclone.oauth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

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

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
