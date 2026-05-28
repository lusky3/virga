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
            VirgaError.Conflict("c"),
            VirgaError.Unknown("u"),
        )
        val labels = errors.map {
            when (it) {
                is VirgaError.Network  -> "network"
                is VirgaError.Auth     -> "auth"
                is VirgaError.Storage  -> "storage"
                is VirgaError.Rclone   -> "rclone"
                is VirgaError.Conflict -> "conflict"
                is VirgaError.Unknown  -> "unknown"
            }
        }
        assertThat(labels).containsExactly("network", "auth", "storage", "rclone", "conflict", "unknown").inOrder()
    }

    // --- Edge cases ---

    @Test fun `empty message is preserved`() {
        val e = VirgaError.Unknown("")
        assertThat(e.message).isEmpty()
    }
}
