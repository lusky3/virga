package app.lusk.virga.feature.remotes

import app.lusk.virga.core.common.model.RemoteOption
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SensitiveKeysTest {
    private fun opt(name: String, isPassword: Boolean = false) = RemoteOption(
        name = name, help = "", type = "string", required = false,
        isPassword = isPassword, default = null, examples = emptyList(), advanced = false,
    )

    @Test fun `derives sensitive keys from isPassword options present in values`() {
        val options = listOf(opt("host"), opt("pass", isPassword = true), opt("key_file"))
        val values = mapOf("host" to "example.com", "pass" to "secret", "key_file" to "/path")
        assertThat(sensitiveKeysFrom(options, values)).containsExactly("pass")
    }

    @Test fun `ignores password options not present in values`() {
        val options = listOf(opt("pass", isPassword = true), opt("pass2", isPassword = true))
        val values = mapOf("pass" to "x")
        assertThat(sensitiveKeysFrom(options, values)).containsExactly("pass")
    }

    @Test fun `ignores password options with blank values`() {
        val options = listOf(opt("pass", isPassword = true))
        val values = mapOf("pass" to "")
        assertThat(sensitiveKeysFrom(options, values)).isEmpty()
    }

    @Test fun `returns empty set when no password options exist`() {
        val options = listOf(opt("host"), opt("port"))
        val values = mapOf("host" to "x", "port" to "22")
        assertThat(sensitiveKeysFrom(options, values)).isEmpty()
    }
}
