package app.lusk.virga.core.common.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RedactionTest {

    @Test fun `secrets collapses token assignments with = or colon`() {
        // Fixtures are obviously-fake placeholders (no real token shapes) so secret
        // scanners don't flag the test source.
        assertThat(Redaction.secrets("access_token=FAKE-TOKEN-VALUE end"))
            .isEqualTo("access_token=<redacted> end")
        assertThat(Redaction.secrets("client_secret: FAKE-SECRET")).isEqualTo("client_secret=<redacted>")
        assertThat(Redaction.secrets("password = FAKE-PW")).isEqualTo("password=<redacted>")
    }

    @Test fun `secrets is case-insensitive and does not leak the value`() {
        val out = Redaction.secrets("Refresh_Token=FAKE-PLACEHOLDER-XYZ")
        assertThat(out).doesNotContain("FAKE-PLACEHOLDER-XYZ")
        assertThat(out).contains("<redacted>")
    }

    @Test fun `secrets leaves ordinary text untouched`() {
        assertThat(Redaction.secrets("Synced 3 files in 2s")).isEqualTo("Synced 3 files in 2s")
    }

    @Test fun `secretsAndPaths strips filesystem and SAF paths`() {
        assertThat(Redaction.secretsAndPaths("failed at /storage/emulated/0/Secret/file.txt"))
            .isEqualTo("failed at <path>")
        assertThat(Redaction.secretsAndPaths("uri content://com.android.providers/tree/primary%3ADocs"))
            .isEqualTo("uri <path>")
        assertThat(Redaction.secretsAndPaths("/data/user/0/app.lusk.virga/files/x"))
            .isEqualTo("<path>")
    }

    @Test fun `secretsAndPaths applies both secret and path redaction`() {
        val out = Redaction.secretsAndPaths("token=abc syncing /sdcard/MyFolder")
        assertThat(out).doesNotContain("abc")
        assertThat(out).doesNotContain("MyFolder")
        assertThat(out).contains("token=<redacted>")
        assertThat(out).contains("<path>")
    }
}
