package app.lusk.virga.core.rclone.oauth

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OAuthConfigTest {
    @Test fun `clientId returns the configured id or empty`() {
        val config = OAuthConfig(
            defaultRedirectUri = "https://x/cb",
            clientIds = mapOf("gdrive" to "id"),
        )
        assertThat(config.clientId("gdrive")).isEqualTo("id")
        assertThat(config.clientId("onedrive")).isEmpty()
    }

    @Test fun `redirectUri falls back to the default when no override`() {
        val config = OAuthConfig(
            defaultRedirectUri = "https://x/cb",
            clientIds = mapOf("gdrive" to "id"),
            redirectUris = mapOf("gdrive" to "https://x/gdrive"),
        )
        assertThat(config.redirectUri("gdrive")).isEqualTo("https://x/gdrive")
        assertThat(config.redirectUri("onedrive")).isEqualTo("https://x/cb")
    }
}
