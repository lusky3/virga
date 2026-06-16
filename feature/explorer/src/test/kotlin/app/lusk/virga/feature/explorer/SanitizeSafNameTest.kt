package app.lusk.virga.feature.explorer

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [sanitizeSafName] — verifies path-traversal prevention
 * on display names returned by SAF or remote-supplied file name strings.
 */
class SanitizeSafNameTest {

    @Test fun `plain filename is returned unchanged`() {
        assertThat(sanitizeSafName("photo.jpg")).isEqualTo("photo.jpg")
    }

    @Test fun `strips forward-slash path prefix leaving last segment`() {
        assertThat(sanitizeSafName("a/b/c.txt")).isEqualTo("c.txt")
    }

    @Test fun `strips backslash path prefix leaving last segment`() {
        assertThat(sanitizeSafName("a\\b\\evil.sh")).isEqualTo("evil.sh")
    }

    @Test fun `replaces dot-dot with underscore`() {
        val result = sanitizeSafName("../etc/passwd")
        assertThat(result).doesNotContain("..")
        assertThat(result).isEqualTo("passwd")
    }

    @Test fun `dot-dot in mid-segment becomes underscore`() {
        val result = sanitizeSafName("some..file.txt")
        assertThat(result).doesNotContain("..")
        assertThat(result).isEqualTo("some_file.txt")
    }

    @Test fun `blank name falls back to default`() {
        assertThat(sanitizeSafName("   ")).isEqualTo("upload")
    }

    @Test fun `empty string falls back to default`() {
        assertThat(sanitizeSafName("")).isEqualTo("upload")
    }

    @Test fun `custom fallback is used for blank input`() {
        assertThat(sanitizeSafName("", fallback = "download")).isEqualTo("download")
    }

    @Test fun `name with only slashes falls back to default`() {
        assertThat(sanitizeSafName("///")).isEqualTo("upload")
    }
}
