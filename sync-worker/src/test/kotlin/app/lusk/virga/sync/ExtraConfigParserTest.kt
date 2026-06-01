package app.lusk.virga.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ExtraConfigParserTest {

    // ---- validateLine: well-formed, allowlisted keys -------------------------

    @Test
    fun `boolean true value is parsed as Boolean`() {
        val result = ExtraConfigParser.validateLine("CheckSum=true")
        assertThat(result).isInstanceOf(ExtraConfigParser.ParseResult.Ok::class.java)
        val ok = result as ExtraConfigParser.ParseResult.Ok
        assertThat(ok.key).isEqualTo("CheckSum")
        assertThat(ok.value).isEqualTo(true)
    }

    @Test
    fun `boolean false value is parsed as Boolean`() {
        val result = ExtraConfigParser.validateLine("SizeOnly=false")
        val ok = result as ExtraConfigParser.ParseResult.Ok
        assertThat(ok.value).isEqualTo(false)
    }

    @Test
    fun `integer value is parsed as Int`() {
        val result = ExtraConfigParser.validateLine("MaxDelete=50")
        val ok = result as ExtraConfigParser.ParseResult.Ok
        assertThat(ok.value).isEqualTo(50)
    }

    @Test
    fun `string value is preserved as String`() {
        val result = ExtraConfigParser.validateLine("BackupDir=remote:backup")
        val ok = result as ExtraConfigParser.ParseResult.Ok
        assertThat(ok.value).isEqualTo("remote:backup")
        assertThat(ok.value).isInstanceOf(String::class.java)
    }

    @Test
    fun `case-insensitive TRUE is parsed as Boolean true`() {
        val result = ExtraConfigParser.validateLine("TrackRenames=TRUE")
        val ok = result as ExtraConfigParser.ParseResult.Ok
        assertThat(ok.value).isEqualTo(true)
    }

    @Test
    fun `blank line returns Ok with empty key`() {
        val result = ExtraConfigParser.validateLine("   ")
        assertThat(result).isInstanceOf(ExtraConfigParser.ParseResult.Ok::class.java)
        assertThat((result as ExtraConfigParser.ParseResult.Ok).key).isEmpty()
    }

    @Test
    fun `OrderBy string value is accepted`() {
        val result = ExtraConfigParser.validateLine("OrderBy=name,asc")
        val ok = result as ExtraConfigParser.ParseResult.Ok
        assertThat(ok.key).isEqualTo("OrderBy")
        assertThat(ok.value).isEqualTo("name,asc")
    }

    // ---- validateLine: unknown keys -----------------------------------------

    @Test
    fun `unknown key returns UnknownKey`() {
        val result = ExtraConfigParser.validateLine("DangerousFlag=true")
        assertThat(result).isInstanceOf(ExtraConfigParser.ParseResult.UnknownKey::class.java)
        val err = result as ExtraConfigParser.ParseResult.UnknownKey
        assertThat(err.key).isEqualTo("DangerousFlag")
        assertThat(err.message).contains("not an allowlisted")
    }

    @Test
    fun `unknown key message lists the allowlist`() {
        val result = ExtraConfigParser.validateLine("FooBar=1")
        val err = result as ExtraConfigParser.ParseResult.UnknownKey
        assertThat(err.message).contains("CheckSum")
    }

    // ---- validateLine: malformed lines --------------------------------------

    @Test
    fun `line without equals sign returns Malformed`() {
        val result = ExtraConfigParser.validateLine("CheckSumtrue")
        assertThat(result).isInstanceOf(ExtraConfigParser.ParseResult.Malformed::class.java)
    }

    @Test
    fun `line starting with equals returns Malformed`() {
        val result = ExtraConfigParser.validateLine("=true")
        assertThat(result).isInstanceOf(ExtraConfigParser.ParseResult.Malformed::class.java)
    }

    // ---- parseToMap ---------------------------------------------------------

    @Test
    fun `parseToMap returns empty map for blank input`() {
        assertThat(ExtraConfigParser.parseToMap("")).isEmpty()
        assertThat(ExtraConfigParser.parseToMap("   \n  ")).isEmpty()
    }

    @Test
    fun `parseToMap parses multiple valid lines`() {
        val map = ExtraConfigParser.parseToMap("CheckSum=true\nMaxDelete=100\nOrderBy=name")
        assertThat(map).containsEntry("CheckSum", true)
        assertThat(map).containsEntry("MaxDelete", 100)
        assertThat(map).containsEntry("OrderBy", "name")
    }

    @Test
    fun `parseToMap silently drops unknown keys`() {
        val map = ExtraConfigParser.parseToMap("CheckSum=true\nUnknownKey=val")
        assertThat(map).containsKey("CheckSum")
        assertThat(map).doesNotContainKey("UnknownKey")
    }

    @Test
    fun `parseToMap silently drops malformed lines`() {
        val map = ExtraConfigParser.parseToMap("CheckSum=true\nmalformedline")
        assertThat(map).containsKey("CheckSum")
        assertThat(map).hasSize(1)
    }

    // ---- firstError ---------------------------------------------------------

    @Test
    fun `firstError returns null for valid block`() {
        val err = ExtraConfigParser.firstError("CheckSum=true\nSizeOnly=false\nMaxDelete=10")
        assertThat(err).isNull()
    }

    @Test
    fun `firstError returns null for empty string`() {
        assertThat(ExtraConfigParser.firstError("")).isNull()
    }

    @Test
    fun `firstError returns message for unknown key`() {
        val err = ExtraConfigParser.firstError("CheckSum=true\nBadKey=1")
        assertThat(err).isNotNull()
        assertThat(err).contains("BadKey")
    }

    @Test
    fun `firstError returns message for malformed line`() {
        val err = ExtraConfigParser.firstError("noequalssign")
        assertThat(err).isNotNull()
        assertThat(err).contains("Key=Value")
    }

    @Test
    fun `firstError ignores blank lines`() {
        val err = ExtraConfigParser.firstError("\n\nCheckSum=true\n\n")
        assertThat(err).isNull()
    }
}
