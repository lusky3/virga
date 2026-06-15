package app.lusk.virga.core.common.validation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RemoteNameValidationTest {

    // --- isValidRemoteName: happy path ---

    @Test fun `isValidRemoteName accepts a simple lowercase name`() {
        assertThat(isValidRemoteName("gdrive")).isTrue()
    }

    @Test fun `isValidRemoteName accepts a name with spaces`() {
        assertThat(isValidRemoteName("My Drive")).isTrue()
    }

    @Test fun `isValidRemoteName accepts a name with underscores dots and hyphens`() {
        assertThat(isValidRemoteName("box_1.2-a")).isTrue()
    }

    @Test fun `isValidRemoteName accepts blank string`() {
        // Blank is not treated as invalid — caller decides whether blank is usable.
        assertThat(isValidRemoteName("")).isTrue()
    }

    // --- isValidRemoteName: length boundary ---

    @Test fun `isValidRemoteName accepts name of exactly MAX_REMOTE_NAME_LENGTH characters`() {
        val name = "a".repeat(MAX_REMOTE_NAME_LENGTH)
        assertThat(isValidRemoteName(name)).isTrue()
    }

    @Test fun `isValidRemoteName rejects name of MAX_REMOTE_NAME_LENGTH plus one characters`() {
        val name = "a".repeat(MAX_REMOTE_NAME_LENGTH + 1)
        assertThat(isValidRemoteName(name)).isFalse()
    }

    // --- isValidRemoteName: forbidden characters ---

    @Test fun `isValidRemoteName rejects name containing a colon`() {
        assertThat(isValidRemoteName("gdrive:path")).isFalse()
    }

    @Test fun `isValidRemoteName rejects name containing a forward slash`() {
        assertThat(isValidRemoteName("my/drive")).isFalse()
    }

    @Test fun `isValidRemoteName rejects name containing a C0 control character`() {
        // U+0007 BEL is in the C0 range (U+0000–U+001F); embed via escape sequence.
        val nameWithControl = "ab\u0007cd"
        assertThat(isValidRemoteName(nameWithControl)).isFalse()
    }

    // --- isValidRemoteName: trim-then-check semantics for colon ---

    @Test fun `isValidRemoteName rejects name where trimmed form contains a colon`() {
        // Leading and trailing spaces are stripped before the colon check, so the
        // colon is still detected even when surrounded by whitespace.
        assertThat(isValidRemoteName("  gdrive:path  ")).isFalse()
    }

    // --- isValidFolderName: happy path ---

    @Test fun `isValidFolderName accepts a plain word`() {
        assertThat(isValidFolderName("Photos")).isTrue()
    }

    @Test fun `isValidFolderName accepts name with internal spaces`() {
        assertThat(isValidFolderName("a b c")).isTrue()
    }

    // --- isValidFolderName: reserved names ---

    @Test fun `isValidFolderName rejects empty string`() {
        assertThat(isValidFolderName("")).isFalse()
    }

    @Test fun `isValidFolderName rejects single dot`() {
        assertThat(isValidFolderName(".")).isFalse()
    }

    @Test fun `isValidFolderName rejects double dot`() {
        assertThat(isValidFolderName("..")).isFalse()
    }

    // --- isValidFolderName: length boundary ---

    @Test fun `isValidFolderName accepts name of exactly MAX_FOLDER_NAME_LENGTH characters`() {
        val name = "a".repeat(MAX_FOLDER_NAME_LENGTH)
        assertThat(isValidFolderName(name)).isTrue()
    }

    @Test fun `isValidFolderName rejects name of MAX_FOLDER_NAME_LENGTH plus one characters`() {
        val name = "a".repeat(MAX_FOLDER_NAME_LENGTH + 1)
        assertThat(isValidFolderName(name)).isFalse()
    }

    // --- isValidFolderName: forbidden characters ---

    @Test fun `isValidFolderName rejects name containing a forward slash`() {
        assertThat(isValidFolderName("a/b")).isFalse()
    }

    @Test fun `isValidFolderName rejects name containing a backslash`() {
        assertThat(isValidFolderName("a\\b")).isFalse()
    }

    @Test fun `isValidFolderName rejects name containing an ISO control character`() {
        // U+0009 HT is an ISO control character; embed via escape sequence.
        assertThat(isValidFolderName("a\u0009b")).isFalse()
    }
}
