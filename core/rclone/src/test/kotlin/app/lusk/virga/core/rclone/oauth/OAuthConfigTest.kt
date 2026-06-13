package app.lusk.virga.core.rclone.oauth

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OAuthConfigTest {
    @Test fun `clientSecret returns the configured secret or null`() {
        val config = OAuthConfig(
            defaultRedirectUri = "https://x/cb",
            clientIds = mapOf("box" to "id"),
            clientSecrets = mapOf("box" to "secret"),
        )
        assertThat(config.clientSecret("box")).isEqualTo("secret")
        assertThat(config.clientSecret("gdrive")).isNull()
    }

    @Test fun `clientSecret treats blank as unset`() {
        val config = OAuthConfig(
            defaultRedirectUri = "https://x/cb",
            clientIds = mapOf("box" to "id"),
            clientSecrets = mapOf("box" to ""),
        )
        assertThat(config.clientSecret("box")).isNull()
    }
}
