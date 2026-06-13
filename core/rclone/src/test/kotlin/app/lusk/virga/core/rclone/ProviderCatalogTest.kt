package app.lusk.virga.core.rclone

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProviderCatalogTest {

    private lateinit var catalog: ProviderCatalog

    @BeforeEach
    fun setUp() {
        val json = this::class.java.getResource("/config-providers-sample.json")!!.readText()
        val root = Json.parseToJsonElement(json).jsonObject
        val providers = parseProviders(root)
        catalog = ProviderCatalog(providers)
    }

    @Test
    fun `credential backends with a password classify as Credential`() {
        assertThat(catalog.setupKind("s3")).isEqualTo(SetupKind.Credential)
        assertThat(catalog.setupKind("sftp")).isEqualTo(SetupKind.Credential)
    }

    @Test
    fun `OAuth backends are detected by token plus client_id`() {
        assertThat(catalog.setupKind("pcloud")).isEqualTo(SetupKind.OAuth(bundled = false))
        // Box is OAuth but no longer bundled — routes to the daemon/BYOK sub-flow.
        assertThat(catalog.setupKind("box")).isEqualTo(SetupKind.OAuth(bundled = false))
    }

    @Test
    fun `allowlisted OAuth backends are bundled`() {
        assertThat(catalog.setupKind("drive")).isEqualTo(SetupKind.OAuth(bundled = true))
    }

    @Test
    fun `wrapper backends classify as Wrapper`() {
        assertThat(catalog.setupKind("crypt")).isEqualTo(SetupKind.Wrapper)
        assertThat(catalog.setupKind("union")).isEqualTo(SetupKind.Wrapper)
    }

    @Test
    fun `local is excluded from the picker`() {
        val types = catalog.pickerEntries().map { it.type }
        assertThat(types).doesNotContain("local")
    }

    @Test
    fun `popular providers are pinned to the top of the picker in order`() {
        val entries = catalog.pickerEntries()
        val types = entries.map { it.type }
        // From fixture: drive, box, s3 are present and pinned (dropbox, onedrive, b2 absent)
        assertThat(types.indexOf("drive")).isEqualTo(0)
        assertThat(types.indexOf("box")).isEqualTo(1)
        assertThat(types.indexOf("s3")).isEqualTo(2)
    }

    @Test
    fun `pinned entries absent from the schema are skipped, not invented`() {
        val types = catalog.pickerEntries().map { it.type }
        assertThat(types).doesNotContain("dropbox")
        assertThat(types).doesNotContain("onedrive")
        assertThat(types).doesNotContain("b2")
    }

    @Test
    fun `unknown backend classifies as Credential by default`() {
        assertThat(catalog.setupKind("nonexistent")).isEqualTo(SetupKind.Credential)
    }
}
