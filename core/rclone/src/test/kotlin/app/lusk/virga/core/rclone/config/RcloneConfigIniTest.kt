package app.lusk.virga.core.rclone.config

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RcloneConfigIniTest {

    // --- parse ---

    @Test fun `parse_roundTrip - parse then serialize preserves sections and keys`() {
        val input = """
            [gdrive]
            type = drive
            client_id = abc

            [s3remote]
            type = s3
            region = us-east-1

        """.trimIndent()
        val sections = RcloneConfigIni.parse(input)
        assertThat(sections).hasSize(2)
        assertThat(sections["gdrive"]).containsEntry("type", "drive")
        assertThat(sections["gdrive"]).containsEntry("client_id", "abc")
        assertThat(sections["s3remote"]).containsEntry("region", "us-east-1")
    }

    @Test fun `parse_ignoresPreamble - text before first section header is dropped`() {
        val input = """
            # global rclone config
            ; some other comment
            version = 2

            [myremote]
            type = drive
        """.trimIndent()
        val sections = RcloneConfigIni.parse(input)
        assertThat(sections).hasSize(1)
        assertThat(sections).containsKey("myremote")
        assertThat(sections).doesNotContainKey("version")
    }

    @Test fun `parse_ignoresBlankLinesAndComments`() {
        val input = """
            [remote1]
            # this is a comment
            ; another comment
            type = drive

            key1 = val1

        """.trimIndent()
        val sections = RcloneConfigIni.parse(input)
        val keys = sections["remote1"] ?: error("section not found")
        assertThat(keys).containsEntry("type", "drive")
        assertThat(keys).containsEntry("key1", "val1")
        assertThat(keys).doesNotContainKey("#")
        assertThat(keys).doesNotContainKey(";")
    }

    @Test fun `parse_valueMayContainEquals - token = abc=def=xyz`() {
        val input = """
            [mytoken]
            token = abc=def=xyz
        """.trimIndent()
        val sections = RcloneConfigIni.parse(input)
        assertThat(sections["mytoken"]).containsEntry("token", "abc=def=xyz")
    }

    // --- merge ---

    @Test fun `merge_overwriteExisting_true - incoming replaces base on collision`() {
        val base = RcloneConfigIni.parse("[remote]\ntype = drive\nclient_id = old\n")
        val incoming = RcloneConfigIni.parse("[remote]\ntype = s3\n")
        val result = RcloneConfigIni.merge(base, incoming, overwriteExisting = true)
        assertThat(result["remote"]).containsEntry("type", "s3")
        assertThat(result["remote"]).doesNotContainKey("client_id")
    }

    @Test fun `merge_overwriteExisting_false - base is kept on collision`() {
        val base = RcloneConfigIni.parse("[remote]\ntype = drive\nclient_id = old\n")
        val incoming = RcloneConfigIni.parse("[remote]\ntype = s3\n")
        val result = RcloneConfigIni.merge(base, incoming, overwriteExisting = false)
        assertThat(result["remote"]).containsEntry("type", "drive")
        assertThat(result["remote"]).containsEntry("client_id", "old")
    }

    @Test fun `merge_newIncomingSectionsAdded`() {
        val base = RcloneConfigIni.parse("[existing]\ntype = drive\n")
        val incoming = RcloneConfigIni.parse("[new1]\ntype = s3\n\n[new2]\ntype = sftp\n")
        val result = RcloneConfigIni.merge(base, incoming, overwriteExisting = true)
        assertThat(result).containsKey("existing")
        assertThat(result).containsKey("new1")
        assertThat(result).containsKey("new2")
    }

    // --- extractSection ---

    @Test fun `extractSection_found - extracts correct single section`() {
        val sections = RcloneConfigIni.parse("""
            [alpha]
            type = drive

            [beta]
            type = s3
        """.trimIndent())
        val extracted = RcloneConfigIni.extractSection(sections, "beta")
        assertThat(extracted).hasSize(1)
        assertThat(extracted).containsKey("beta")
        assertThat(extracted["beta"]).containsEntry("type", "s3")
    }

    @Test fun `extractSection_notFound - returns empty map`() {
        val sections = RcloneConfigIni.parse("[alpha]\ntype = drive\n")
        val extracted = RcloneConfigIni.extractSection(sections, "ghost")
        assertThat(extracted).isEmpty()
    }

    // --- redact ---

    @Test fun `redact_masksOnlySensitiveKeys - type, client_id, region visible and pass, token masked`() {
        val text = "[remote]\ntype = drive\nclient_id = myid\npass = secret\ntoken = tok\n"
        val sections = RcloneConfigIni.parse(text)
        val redacted = RcloneConfigIni.redact(sections)
        val keys = redacted["remote"]!!
        assertThat(keys["type"]).isEqualTo("drive")
        assertThat(keys["client_id"]).isEqualTo("myid")
        assertThat(keys["pass"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(keys["token"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
    }

    @Test fun `redact_caseInsensitive - PASS and Token are masked`() {
        val text = "[remote]\nPASS = secret\nToken = tok123\nclient_id = pub\n"
        val sections = RcloneConfigIni.parse(text)
        val redacted = RcloneConfigIni.redact(sections)
        val keys = redacted["remote"]!!
        assertThat(keys["PASS"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(keys["Token"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(keys["client_id"]).isEqualTo("pub")
    }

    @Test fun `redact_preservesStructure - other keys and sections remain`() {
        val text = """
            [s3]
            type = s3
            region = us-east-1
            secret_access_key = mysecret

            [gdrive]
            type = drive
            client_id = gid

        """.trimIndent()
        val sections = RcloneConfigIni.parse(text)
        val redacted = RcloneConfigIni.redact(sections)
        assertThat(redacted).hasSize(2)
        assertThat(redacted["s3"]!!["region"]).isEqualTo("us-east-1")
        assertThat(redacted["s3"]!!["secret_access_key"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(redacted["gdrive"]!!["client_id"]).isEqualTo("gid")
    }

    /**
     * Verify the substring heuristic catches additional real rclone IsPassword fields
     * that the old fixed set missed, and that structural keys stay visible.
     */
    @Test fun `redact_substringHeuristic - masks extended rclone secret keys`() {
        val text = """
            [myremote]
            type = swift
            password2 = p2
            api_key = k
            api_password = ap
            secret = s
            plex_password = pp
            application_credential_secret = acs
            otp_secret_key = osk
            client_certificate_password = ccp
            library_key = lk
            auth = https://identity.example.com/v3
            region = RegionOne
            endpoint = https://storage.example.com
            provider = Rackspace
            url = https://remote.example.com
            host = sftp.example.com
            port = 22
        """.trimIndent()
        val sections = RcloneConfigIni.parse(text)
        val redacted = RcloneConfigIni.redact(sections)["myremote"]!!

        // Must be masked (contain pass/secret/token/key/credential)
        assertThat(redacted["password2"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(redacted["api_key"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(redacted["api_password"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(redacted["secret"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(redacted["plex_password"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(redacted["application_credential_secret"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(redacted["otp_secret_key"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(redacted["client_certificate_password"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)
        assertThat(redacted["library_key"]).isEqualTo(RcloneConfigIni.REDACTED_PLACEHOLDER)

        // Must stay visible (auth is a URL, not a secret; structural config keys)
        assertThat(redacted["auth"]).isEqualTo("https://identity.example.com/v3")
        assertThat(redacted["region"]).isEqualTo("RegionOne")
        assertThat(redacted["endpoint"]).isEqualTo("https://storage.example.com")
        assertThat(redacted["provider"]).isEqualTo("Rackspace")
        assertThat(redacted["url"]).isEqualTo("https://remote.example.com")
        assertThat(redacted["host"]).isEqualTo("sftp.example.com")
        assertThat(redacted["port"]).isEqualTo("22")
        assertThat(redacted["type"]).isEqualTo("swift")
    }

    @Test fun `malformedInput_doesNotThrow - lines without = in section body are skipped`() {
        val text = """
            [good]
            type = drive
            this line has no equals sign
            valid_key = value
        """.trimIndent()
        val sections = RcloneConfigIni.parse(text)
        val keys = sections["good"]!!
        assertThat(keys).containsEntry("type", "drive")
        assertThat(keys).containsEntry("valid_key", "value")
        assertThat(keys).doesNotContainKey("this line has no equals sign")
    }
}
