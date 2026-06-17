package app.lusk.virga.feature.remotes

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [backendSubFormType]. No Android dependencies; runs on the
 * host JVM via JUnit 5.
 *
 * Coverage:
 *  - Canonical lowercase tokens → correct kind
 *  - Case-insensitive variants (uppercase, mixed)
 *  - Unknown types → null
 *  - Empty string → null
 */
class BackendSubFormTypeTest {

    // ── canonical lowercase ───────────────────────────────────────────────────

    @Test
    fun `s3 lowercase returns S3`() {
        assertThat(backendSubFormType("s3")).isEqualTo(BackendSubFormKind.S3)
    }

    @Test
    fun `sftp lowercase returns SFTP`() {
        assertThat(backendSubFormType("sftp")).isEqualTo(BackendSubFormKind.SFTP)
    }

    @Test
    fun `webdav lowercase returns WEBDAV`() {
        assertThat(backendSubFormType("webdav")).isEqualTo(BackendSubFormKind.WEBDAV)
    }

    // ── case-insensitivity ────────────────────────────────────────────────────

    @Test
    fun `S3 uppercase returns S3`() {
        assertThat(backendSubFormType("S3")).isEqualTo(BackendSubFormKind.S3)
    }

    @Test
    fun `SFTP uppercase returns SFTP`() {
        assertThat(backendSubFormType("SFTP")).isEqualTo(BackendSubFormKind.SFTP)
    }

    @Test
    fun `WebDAV mixed-case returns WEBDAV`() {
        assertThat(backendSubFormType("WebDAV")).isEqualTo(BackendSubFormKind.WEBDAV)
    }

    @Test
    fun `WEBDAV uppercase returns WEBDAV`() {
        assertThat(backendSubFormType("WEBDAV")).isEqualTo(BackendSubFormKind.WEBDAV)
    }

    // ── unknown types → null ──────────────────────────────────────────────────

    @Test
    fun `drive returns null`() {
        assertThat(backendSubFormType("drive")).isNull()
    }

    @Test
    fun `dropbox returns null`() {
        assertThat(backendSubFormType("dropbox")).isNull()
    }

    @Test
    fun `empty string returns null`() {
        assertThat(backendSubFormType("")).isNull()
    }
}
