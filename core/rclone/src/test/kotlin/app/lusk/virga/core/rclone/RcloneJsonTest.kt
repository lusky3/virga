package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.error.VirgaError
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
 * Unit tests for [putFilters], [putConfig], [isAuthError], and [classifyJobError] in RcloneJson.kt.
 * No daemon, no coroutines — pure logic.
 */
class RcloneJsonTest {

    // --- isAuthError ------------------------------------------------------------

    @Test fun `isAuthError matches oauth2 marker`() {
        assertThat(isAuthError("oauth2: cannot fetch token: 401 Unauthorized")).isTrue()
    }

    @Test fun `isAuthError matches invalid_grant`() {
        assertThat(isAuthError("Error: invalid_grant: Token has been expired or revoked")).isTrue()
    }

    @Test fun `isAuthError matches token expired`() {
        assertThat(isAuthError("token expired for remote")).isTrue()
    }

    @Test fun `isAuthError matches expired token`() {
        assertThat(isAuthError("The expired token cannot be refreshed")).isTrue()
    }

    @Test fun `isAuthError matches couldn't fetch token`() {
        assertThat(isAuthError("couldn't fetch token: server returned 401")).isTrue()
    }

    @Test fun `isAuthError matches failed to refresh`() {
        assertThat(isAuthError("failed to refresh the OAuth token")).isTrue()
    }

    @Test fun `isAuthError matches 401 with adjacent context - status 401`() {
        assertThat(isAuthError("http status 401")).isTrue()
    }

    @Test fun `isAuthError matches 401 with adjacent context - 401 unauthorized`() {
        assertThat(isAuthError("server returned 401 Unauthorized")).isTrue()
    }

    @Test fun `isAuthError matches 403 with adjacent context - 403 forbidden`() {
        assertThat(isAuthError("403 Forbidden")).isTrue()
    }

    @Test fun `isAuthError does NOT match bare 401 digit substring in byte count`() {
        // "Transferred: 1401 / 2401 Bytes" must NOT be flagged as auth error.
        assertThat(isAuthError("Transferred: 1401 / 2401 Bytes, 100%, 0 Bytes/s, ETA -")).isFalse()
    }

    @Test fun `isAuthError does NOT match bare 403 digit substring in byte count`() {
        assertThat(isAuthError("Transferred: 7403 / 8403 Bytes, 88%")).isFalse()
    }

    @Test fun `isAuthError matches unauthorized case insensitive`() {
        assertThat(isAuthError("UNAUTHORIZED access")).isTrue()
    }

    @Test fun `isAuthError matches permission denied`() {
        assertThat(isAuthError("permission denied for bucket")).isTrue()
    }

    @Test fun `isAuthError matches accessdenied`() {
        assertThat(isAuthError("AccessDenied")).isTrue()
    }

    @Test fun `isAuthError matches access denied with space`() {
        assertThat(isAuthError("Access Denied: operation not permitted")).isTrue()
    }

    @Test fun `isAuthError matches invalid_client`() {
        assertThat(isAuthError("error: invalid_client")).isTrue()
    }

    @Test fun `isAuthError matches revoked`() {
        assertThat(isAuthError("token has been revoked")).isTrue()
    }

    @Test fun `isAuthError does not match a plain directory-not-found error`() {
        assertThat(isAuthError("directory not found: /Backup")).isFalse()
    }

    @Test fun `isAuthError does not match a generic network error`() {
        assertThat(isAuthError("connection reset by peer")).isFalse()
    }

    // --- classifyJobError does not misclassify auth as transient ---------------

    @Test fun `classifyJobError returns Rclone for oauth2 error not Network`() {
        val err = classifyJobError("oauth2: cannot fetch token: 401 Unauthorized")
        assertThat(err).isInstanceOf(VirgaError.Rclone::class.java)
    }

    @Test fun `classifyJobError still returns Network for timeout`() {
        val err = classifyJobError("timeout waiting for connection")
        assertThat(err).isInstanceOf(VirgaError.Network::class.java)
    }

    @Test fun `classifyJobError still returns Rclone for directory not found`() {
        val err = classifyJobError("directory not found")
        assertThat(err).isInstanceOf(VirgaError.Rclone::class.java)
    }

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

    // --- B7: putConfig ConflictResolve (bisync only) -----------------------

    @Test
    fun `putConfig emits ConflictResolve when BisyncOptions conflictResolve is set`() {
        val obj = buildJsonObject {
            putConfig(BisyncOptions(conflictResolve = "newer"))
        }
        val cfg = obj["_config"]!!.jsonObject
        assertThat(cfg["ConflictResolve"]?.jsonPrimitive?.contentOrNull).isEqualTo("newer")
    }

    @Test
    fun `putConfig omits ConflictResolve when BisyncOptions conflictResolve is null`() {
        val obj = buildJsonObject {
            putConfig(BisyncOptions(conflictResolve = null))
        }
        val cfg = obj["_config"]!!.jsonObject
        assertThat(cfg.containsKey("ConflictResolve")).isFalse()
    }

    @Test
    fun `putConfig omits ConflictResolve when BisyncOptions conflictResolve is blank`() {
        val obj = buildJsonObject {
            putConfig(BisyncOptions(conflictResolve = ""))
        }
        val cfg = obj["_config"]!!.jsonObject
        assertThat(cfg.containsKey("ConflictResolve")).isFalse()
    }

    @Test
    fun `putConfig does not emit ConflictResolve for SyncOptions even with extraConfig`() {
        // SyncOptions is a one-way options type; ConflictResolve only applies to bisync.
        val obj = buildJsonObject {
            putConfig(SyncOptions(direction = SyncDirection.UPLOAD))
        }
        val cfg = obj["_config"]!!.jsonObject
        assertThat(cfg.containsKey("ConflictResolve")).isFalse()
    }
}
