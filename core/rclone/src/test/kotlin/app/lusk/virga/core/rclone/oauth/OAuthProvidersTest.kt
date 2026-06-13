package app.lusk.virga.core.rclone.oauth

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OAuthProvidersTest {
    @Test fun `Box is a bundled provider that requires a client secret`() {
        val box = OAuthProviders.byId("box")
        assertThat(box).isNotNull()
        assertThat(box!!.type).isEqualTo("box")
        assertThat(box.requiresClientSecret).isTrue()
    }

    @Test fun `the PKCE three do not require a client secret`() {
        listOf("gdrive", "onedrive", "dropbox").forEach { id ->
            assertThat(OAuthProviders.byId(id)!!.requiresClientSecret).isFalse()
        }
    }

    @Test fun `All lists exactly the four bundled providers`() {
        assertThat(OAuthProviders.All.map { it.id })
            .containsExactly("gdrive", "onedrive", "dropbox", "box")
    }
}
