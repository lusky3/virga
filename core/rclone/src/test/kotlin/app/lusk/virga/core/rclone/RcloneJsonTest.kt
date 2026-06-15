package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.model.SyncDirection
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Unit tests for [putFilters] and [putConfig] in RcloneJson.kt.
 * No daemon, no coroutines — pure JSON construction.
 */
class RcloneJsonTest {

    // --- putConfig: MaxTransfer / CutoffMode (B6) ---------------------------

    @Test
    fun `putConfig emits MaxTransfer and CutoffMode CAUTIOUS when maxTransfer is set`() {
        val obj = buildJsonObject {
            putConfig(SyncOptions(direction = SyncDirection.UPLOAD, maxTransfer = "10G"))
        }
        val cfg = obj["_config"]!!.jsonObject
        assertThat(cfg["MaxTransfer"]?.jsonPrimitive?.contentOrNull).isEqualTo("10G")
        assertThat(cfg["CutoffMode"]?.jsonPrimitive?.contentOrNull).isEqualTo("CAUTIOUS")
    }

    @Test
    fun `putConfig omits MaxTransfer and CutoffMode when maxTransfer is blank`() {
        val obj = buildJsonObject {
            putConfig(SyncOptions(direction = SyncDirection.UPLOAD, maxTransfer = ""))
        }
        val cfg = obj["_config"]!!.jsonObject
        assertThat(cfg.containsKey("MaxTransfer")).isFalse()
        assertThat(cfg.containsKey("CutoffMode")).isFalse()
    }

    @Test
    fun `putConfig omits MaxTransfer and CutoffMode when maxTransfer is null`() {
        val obj = buildJsonObject {
            putConfig(SyncOptions(direction = SyncDirection.UPLOAD))
        }
        val cfg = obj["_config"]!!.jsonObject
        assertThat(cfg.containsKey("MaxTransfer")).isFalse()
        assertThat(cfg.containsKey("CutoffMode")).isFalse()
    }

    @Test
    fun `putFilters omits _filter block when all inputs are blank`() {
        val obj = buildJsonObject {
            putFilters(emptyList(), null, null, null, null)
        }
        assertThat(obj.containsKey("_filter")).isFalse()
    }

    @Test
    fun `putFilters omits _filter block when filters empty and size-age all null`() {
        val obj = buildJsonObject { putFilters(emptyList()) }
        assertThat(obj.containsKey("_filter")).isFalse()
    }

    @Test
    fun `putFilters emits FilterRule array for non-empty filter list`() {
        val obj = buildJsonObject {
            putFilters(listOf("- *.tmp", "+ *.jpg"))
        }
        val filter = obj["_filter"]?.jsonObject
        assertThat(filter).isNotNull()
        val rules = filter!!["FilterRule"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull }
        assertThat(rules).containsExactly("- *.tmp", "+ *.jpg").inOrder()
    }

    @Test
    fun `putFilters emits MinSize when provided`() {
        val obj = buildJsonObject {
            putFilters(emptyList(), minSize = "10M")
        }
        val filter = obj["_filter"]?.jsonObject
        assertThat(filter).isNotNull()
        assertThat(filter!!["MinSize"]?.jsonPrimitive?.contentOrNull).isEqualTo("10M")
        assertThat(filter.containsKey("FilterRule")).isFalse()
    }

    @Test
    fun `putFilters emits MaxSize when provided`() {
        val obj = buildJsonObject {
            putFilters(emptyList(), maxSize = "1G")
        }
        assertThat(obj["_filter"]?.jsonObject?.get("MaxSize")?.jsonPrimitive?.contentOrNull).isEqualTo("1G")
    }

    @Test
    fun `putFilters emits MinAge when provided`() {
        val obj = buildJsonObject {
            putFilters(emptyList(), minAge = "30d")
        }
        assertThat(obj["_filter"]?.jsonObject?.get("MinAge")?.jsonPrimitive?.contentOrNull).isEqualTo("30d")
    }

    @Test
    fun `putFilters emits MaxAge when provided`() {
        val obj = buildJsonObject {
            putFilters(emptyList(), maxAge = "7d")
        }
        assertThat(obj["_filter"]?.jsonObject?.get("MaxAge")?.jsonPrimitive?.contentOrNull).isEqualTo("7d")
    }

    @Test
    fun `putFilters emits all size-age keys alongside FilterRule when both are set`() {
        val obj = buildJsonObject {
            putFilters(
                filters = listOf("+ *.jpg"),
                minSize = "100k",
                maxSize = "500M",
                minAge = "1d",
                maxAge = "365d",
            )
        }
        val filter = obj["_filter"]!!.jsonObject
        assertThat(filter["FilterRule"]?.jsonArray).hasSize(1)
        assertThat(filter["MinSize"]?.jsonPrimitive?.contentOrNull).isEqualTo("100k")
        assertThat(filter["MaxSize"]?.jsonPrimitive?.contentOrNull).isEqualTo("500M")
        assertThat(filter["MinAge"]?.jsonPrimitive?.contentOrNull).isEqualTo("1d")
        assertThat(filter["MaxAge"]?.jsonPrimitive?.contentOrNull).isEqualTo("365d")
    }

    @Test
    fun `putFilters omits blank size-age keys from _filter`() {
        val obj = buildJsonObject {
            putFilters(emptyList(), minSize = "10M", maxSize = "", minAge = null, maxAge = "  ")
        }
        val filter = obj["_filter"]!!.jsonObject
        assertThat(filter.containsKey("MinSize")).isTrue()
        assertThat(filter.containsKey("MaxSize")).isFalse()
        assertThat(filter.containsKey("MinAge")).isFalse()
        assertThat(filter.containsKey("MaxAge")).isFalse()
    }
}
