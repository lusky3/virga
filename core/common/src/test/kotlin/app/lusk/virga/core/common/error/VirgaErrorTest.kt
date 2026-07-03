package app.lusk.virga.core.common.error

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class VirgaErrorTest {

    // --- Hierarchy / instanceof checks ---

    @Test fun `Network is a VirgaError`() {
        val e = VirgaError.Network("timeout")
        assertThat(e).isInstanceOf(VirgaError::class.java)
        assertThat(e.message).isEqualTo("timeout")
        assertThat(e.cause).isNull()
    }

    @Test fun `Network wraps cause`() {
        val cause = RuntimeException("io")
        val e = VirgaError.Network("failed", cause)
        assertThat(e.cause).isSameInstanceAs(cause)
    }

    @Test fun `Auth carries remote name`() {
        val e = VirgaError.Auth(remote = "gdrive", message = "token expired")
        assertThat(e.remote).isEqualTo("gdrive")
        assertThat(e.message).isEqualTo("token expired")
    }

    @Test fun `Rclone carries optional exitCode`() {
        val withCode = VirgaError.Rclone(exitCode = 1, message = "crash")
        assertThat(withCode.exitCode).isEqualTo(1)

        val withoutCode = VirgaError.Rclone(message = "no id")
        assertThat(withoutCode.exitCode).isNull()
    }

    @Test fun `Storage has no extra fields`() {
        val e = VirgaError.Storage("disk full")
        assertThat(e.message).isEqualTo("disk full")
    }

    @Test fun `Conflict carries message`() {
        val e = VirgaError.Conflict("both sides modified")
        assertThat(e.message).isEqualTo("both sides modified")
    }

    @Test fun `Unknown carries message`() {
        val e = VirgaError.Unknown("???")
        assertThat(e).isInstanceOf(VirgaError::class.java)
    }

    // --- Sealed class exhaustiveness (compile-time, verified by when) ---

    @Test fun `sealed when covers all subtypes`() {
        val errors: List<VirgaError> = listOf(
            VirgaError.Network("n"),
            VirgaError.Auth("r", "a"),
            VirgaError.Storage("s"),
            VirgaError.Rclone(message = "rc"),
            VirgaError.Stall(message = "st"),
            VirgaError.Conflict("c"),
            VirgaError.Unknown("u"),
        )
        val labels = errors.map {
            when (it) {
                is VirgaError.Network  -> "network"
                is VirgaError.Auth     -> "auth"
                is VirgaError.Storage  -> "storage"
                is VirgaError.Rclone   -> "rclone"
                is VirgaError.Stall    -> "stall"
                is VirgaError.Conflict -> "conflict"
                is VirgaError.Unknown  -> "unknown"
            }
        }
        assertThat(labels).containsExactly("network", "auth", "storage", "rclone", "stall", "conflict", "unknown").inOrder()
    }

    // --- Edge cases ---

    @Test fun `empty message is preserved`() {
        val e = VirgaError.Unknown("")
        assertThat(e.message).isEmpty()
    }

    @Test
    fun `Stall carries the in-flight file and message`() {
        val e = VirgaError.Stall(file = "DCIM/IMG_1.jpg", message = "stalled")
        assertThat(e).isInstanceOf(VirgaError::class.java)
        assertThat(e.file).isEqualTo("DCIM/IMG_1.jpg")
        assertThat(e.message).isEqualTo("stalled")
    }

    @Test
    fun `Stall file defaults to null`() {
        assertThat(VirgaError.Stall(message = "stalled").file).isNull()
    }

    @Test
    fun `Stall toUserMessage surfaces the carried message`() {
        val msg = VirgaError.Stall(file = "DCIM/IMG_1.jpg", message = "no progress, last read DCIM/IMG_1.jpg")
            .toUserMessage()
        assertThat(msg).isEqualTo("no progress, last read DCIM/IMG_1.jpg")
    }

    @Test
    fun `Stall toUserMessage falls back to neutral non-retry copy when message blank`() {
        val msg = VirgaError.Stall(message = "").toUserMessage()
        assertThat(msg).contains("stalled")
        // A stall is non-retryable, so the copy must not tell the user to retry.
        assertThat(msg).doesNotContain("Try again")
    }

    // --- toUserMessage() copy for every VirgaError variant ---

    @Test fun `Network toUserMessage is the offline copy`() {
        assertThat(VirgaError.Network("timeout").toUserMessage())
            .isEqualTo("No internet connection. Check your network and retry.")
    }

    @Test fun `Auth toUserMessage names the remote`() {
        assertThat(VirgaError.Auth(remote = "gdrive", message = "expired").toUserMessage())
            .isEqualTo("Sign-in expired for \"gdrive\". Re-add the remote to reconnect.")
    }

    @Test fun `Storage toUserMessage surfaces the carried message`() {
        assertThat(VirgaError.Storage("disk full").toUserMessage())
            .isEqualTo("Storage error: disk full")
    }

    @Test fun `Storage toUserMessage falls back when message blank`() {
        assertThat(VirgaError.Storage("").toUserMessage())
            .isEqualTo("Storage error: check available space and permissions")
    }

    @Test fun `Rclone toUserMessage surfaces the real rclone error`() {
        assertThat(VirgaError.Rclone(message = "directory not found").toUserMessage())
            .isEqualTo("directory not found")
    }

    @Test fun `Rclone toUserMessage fallback includes the exit code`() {
        assertThat(VirgaError.Rclone(exitCode = 7, message = "").toUserMessage())
            .isEqualTo("Sync engine error (code 7). Try again.")
    }

    @Test fun `Rclone toUserMessage fallback omits the code when absent`() {
        assertThat(VirgaError.Rclone(message = "").toUserMessage())
            .isEqualTo("Sync engine error. Try again.")
    }

    @Test fun `Conflict toUserMessage points at the Conflicts screen`() {
        assertThat(VirgaError.Conflict("both sides modified").toUserMessage())
            .isEqualTo("Conflict detected. Open the Conflicts screen to resolve.")
    }

    @Test fun `Unknown toUserMessage surfaces the carried message`() {
        assertThat(VirgaError.Unknown("boom").toUserMessage()).isEqualTo("boom")
    }

    @Test fun `Unknown toUserMessage falls back when message blank`() {
        assertThat(VirgaError.Unknown("").toUserMessage())
            .isEqualTo("Something went wrong. Tap to retry.")
    }

    // --- Throwable.toUserMessage() ---

    @Test fun `Throwable toUserMessage maps a VirgaError to its friendly copy`() {
        val t: Throwable = VirgaError.Network("x")
        assertThat(t.toUserMessage())
            .isEqualTo("No internet connection. Check your network and retry.")
    }

    @Test fun `Throwable toUserMessage surfaces a plain exception message`() {
        assertThat(RuntimeException("kaboom").toUserMessage()).isEqualTo("kaboom")
    }

    @Test fun `Throwable toUserMessage falls back for a blank message`() {
        assertThat(RuntimeException("").toUserMessage())
            .isEqualTo("Something went wrong. Tap to retry.")
    }

    @Test fun `Throwable toUserMessage falls back for a null message`() {
        assertThat(RuntimeException().toUserMessage())
            .isEqualTo("Something went wrong. Tap to retry.")
    }
}
