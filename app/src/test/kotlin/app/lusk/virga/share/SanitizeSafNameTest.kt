package app.lusk.virga.share

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [sanitizeSafName].
 *
 * Pure JVM — no Android runtime required. The function has no Android imports;
 * it operates only on [String].
 */
class SanitizeSafNameTest {

    // --- normal names pass through unchanged ---

    @Test
    fun `should return the name unchanged when it is a plain filename`() {
        assertThat(sanitizeSafName("photo.jpg")).isEqualTo("photo.jpg")
    }

    @Test
    fun `should return the name unchanged when it contains spaces`() {
        assertThat(sanitizeSafName("my document.pdf")).isEqualTo("my document.pdf")
    }

    // --- path separators are stripped ---

    @Test
    fun `should strip leading forward-slash path components`() {
        // substringAfterLast('/') keeps only the last segment
        assertThat(sanitizeSafName("/etc/passwd")).isEqualTo("passwd")
    }

    @Test
    fun `should strip multiple forward-slash path components`() {
        assertThat(sanitizeSafName("a/b/c/evil.txt")).isEqualTo("evil.txt")
    }

    @Test
    fun `should strip back-slash path components`() {
        assertThat(sanitizeSafName("C:\\Users\\file.txt")).isEqualTo("file.txt")
    }

    @Test
    fun `should strip mixed slash path components`() {
        // forward-slash is applied first in substringAfterLast; result is then
        // re-processed through the back-slash step.
        assertThat(sanitizeSafName("a/b\\c/file.dat")).isEqualTo("file.dat")
    }

    // --- ".." traversal sequences are neutralised ---

    @Test
    fun `should replace double-dot with underscore`() {
        assertThat(sanitizeSafName("..")).isEqualTo("_")
    }

    @Test
    fun `should replace double-dot in traversal attempt`() {
        // After stripping path segments the result might still contain ".." inline.
        val result = sanitizeSafName("../../evil.txt")
        assertThat(result).doesNotContain("..")
    }

    @Test
    fun `should produce safe name for classic traversal path`() {
        // "../../evil.txt" → substringAfterLast('/') → "evil.txt" (no ".." left)
        assertThat(sanitizeSafName("../../evil.txt")).isEqualTo("evil.txt")
    }

    @Test
    fun `should neutralise double-dot embedded within a filename segment`() {
        val result = sanitizeSafName("foo..bar")
        assertThat(result).doesNotContain("..")
    }

    // --- blank / empty input falls back to the default ---

    @Test
    fun `should return the fallback when the input is blank`() {
        assertThat(sanitizeSafName("   ")).isEqualTo("upload")
    }

    @Test
    fun `should return the fallback when the input is empty`() {
        assertThat(sanitizeSafName("")).isEqualTo("upload")
    }

    @Test
    fun `should return a custom fallback when the input is blank`() {
        assertThat(sanitizeSafName("   ", fallback = "file")).isEqualTo("file")
    }

    // --- trimming ---

    @Test
    fun `should trim surrounding whitespace`() {
        assertThat(sanitizeSafName("  notes.txt  ")).isEqualTo("notes.txt")
    }

    @Test
    fun `should collapse to blank and use fallback when the name is only whitespace after stripping`() {
        // A purely whitespace segment after path removal → blank → fallback.
        assertThat(sanitizeSafName("path/   ")).isEqualTo("upload")
    }
}
