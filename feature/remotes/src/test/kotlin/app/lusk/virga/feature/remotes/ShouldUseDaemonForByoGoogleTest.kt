package app.lusk.virga.feature.remotes

import app.lusk.virga.core.rclone.oauth.OAuthProviders
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [shouldUseDaemonForByoGoogle].
 *
 * The function is a pure top-level predicate — no Android framework, no coroutines, no mocks.
 * It returns true iff:
 *   - provider id == OAuthProviders.GoogleDrive.id ("gdrive"), AND
 *   - override (client-id) is non-null and non-blank, AND
 *   - secret (client-secret) is non-null and non-blank.
 *
 * Extracted from SystemOAuthFlow so it can be tested in isolation; this class covers every
 * meaningful branch exhaustively.
 */
class ShouldUseDaemonForByoGoogleTest {

    private val gdrive = OAuthProviders.GoogleDrive.id   // "gdrive"
    private val onedrive = OAuthProviders.OneDrive.id    // "onedrive"
    private val dropbox = OAuthProviders.Dropbox.id      // "dropbox"

    // -------------------------------------------------------------------------
    // True case: all three conditions satisfied
    // -------------------------------------------------------------------------

    @Test
    fun `should return true when provider is gdrive and both override and secret are present`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = gdrive,
            override = "byo-client-id",
            secret = "byo-secret-value",
        )
        assertThat(result).isTrue()
    }

    // -------------------------------------------------------------------------
    // False: wrong provider (even with override and secret)
    // -------------------------------------------------------------------------

    @Test
    fun `should return false when provider is onedrive even with override and secret`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = onedrive,
            override = "byo-client-id",
            secret = "byo-secret-value",
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `should return false when provider is dropbox even with override and secret`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = dropbox,
            override = "byo-client-id",
            secret = "byo-secret-value",
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `should return false when provider is an unknown id even with override and secret`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = "pcloud",
            override = "byo-client-id",
            secret = "byo-secret-value",
        )
        assertThat(result).isFalse()
    }

    // -------------------------------------------------------------------------
    // False: override (client-id) null or blank
    // -------------------------------------------------------------------------

    @Test
    fun `should return false when override is null even for gdrive with secret`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = gdrive,
            override = null,
            secret = "byo-secret-value",
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `should return false when override is blank even for gdrive with secret`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = gdrive,
            override = "   ",
            secret = "byo-secret-value",
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `should return false when override is empty string even for gdrive with secret`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = gdrive,
            override = "",
            secret = "byo-secret-value",
        )
        assertThat(result).isFalse()
    }

    // -------------------------------------------------------------------------
    // False: secret null or blank
    // -------------------------------------------------------------------------

    @Test
    fun `should return false when secret is null even for gdrive with override`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = gdrive,
            override = "byo-client-id",
            secret = null,
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `should return false when secret is blank even for gdrive with override`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = gdrive,
            override = "byo-client-id",
            secret = "   ",
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `should return false when secret is empty string even for gdrive with override`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = gdrive,
            override = "byo-client-id",
            secret = "",
        )
        assertThat(result).isFalse()
    }

    // -------------------------------------------------------------------------
    // False: both override and secret are null
    // -------------------------------------------------------------------------

    @Test
    fun `should return false when both override and secret are null for gdrive`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = gdrive,
            override = null,
            secret = null,
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `should return false when both override and secret are null for non-gdrive provider`() {
        val result = shouldUseDaemonForByoGoogle(
            providerId = onedrive,
            override = null,
            secret = null,
        )
        assertThat(result).isFalse()
    }
}
