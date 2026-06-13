package app.lusk.virga.core.rclone.oauth

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OAuthProvidersTest {
    @Test fun `Box is not a bundled provider`() {
        // Box moved out of the bundled four — it now uses the daemon/BYOK path,
        // so it must not appear among the bundled OAuth providers.
        assertThat(OAuthProviders.byId("box")).isNull()
    }

    @Test fun `All lists exactly the three bundled PKCE providers`() {
        assertThat(OAuthProviders.All.map { it.id })
            .containsExactly("gdrive", "onedrive", "dropbox")
    }
}
