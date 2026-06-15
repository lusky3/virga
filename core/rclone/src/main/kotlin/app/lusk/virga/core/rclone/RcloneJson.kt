package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.RemoteOption
import app.lusk.virga.core.common.model.RemoteProvider
import app.lusk.virga.core.common.model.SyncProgress
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pure JSON ⇄ domain mapping and error-classification helpers for the rclone RC
 * API. Extracted from [RcloneEngineImpl] so the engine class stays orchestration-
 * only (and under the 500-line limit) and these stay trivially unit-testable —
 * none of them touch the daemon, the lock, or any Android dependency.
 */

/**
 * Maps a `config/providers` response into [RemoteProvider]/[RemoteOption] models.
 * Every field is parsed defensively; missing keys use safe defaults so a future
 * rclone schema change degrades the UI gracefully rather than breaking it.
 */
internal fun parseProviders(root: JsonObject): List<RemoteProvider> {
    val providerArray = root["providers"]?.jsonArray ?: return emptyList()
    return providerArray.map { elem ->
        val obj = elem.jsonObject
        val opts = obj["Options"]?.jsonArray?.map { optElem ->
            val o = optElem.jsonObject
            val examples = o["Examples"]?.jsonArray?.mapNotNull { ex ->
                val exObj = ex.jsonObject
                val v = exObj["Value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                v to (exObj["Help"]?.jsonPrimitive?.contentOrNull.orEmpty())
            } ?: emptyList()
            RemoteOption(
                name = o["Name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                help = o["Help"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                type = o["Type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                required = o["Required"]?.jsonPrimitive?.booleanOrNull ?: false,
                isPassword = o["IsPassword"]?.jsonPrimitive?.booleanOrNull ?: false,
                default = o["DefaultStr"]?.jsonPrimitive?.contentOrNull
                    ?: o["Default"]?.jsonPrimitive?.contentOrNull,
                examples = examples,
                advanced = o["Advanced"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        } ?: emptyList()
        RemoteProvider(
            name = obj["Name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            description = obj["Description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            options = opts,
        )
    }
}

/** Maps a `core/stats` (or job/status final stats) JSON object into [SyncProgress]. */
internal fun JsonObject.toSyncProgress(): SyncProgress = SyncProgress(
    bytesTransferred = this["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
    totalBytes = this["totalBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
    speedBytesPerSec = this["speed"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
    transferredFiles = this["transfers"]?.jsonPrimitive?.intOrNull ?: 0,
    totalFiles = this["totalTransfers"]?.jsonPrimitive?.intOrNull ?: 0,
    etaSeconds = this["eta"]?.jsonPrimitive?.longOrNull,
    errors = this["errors"]?.jsonPrimitive?.intOrNull ?: 0,
    deletes = this["deletes"]?.jsonPrimitive?.intOrNull ?: 0,
)

/**
 * Returns true when [err] looks like an OAuth / credential failure. Auth errors must
 * NOT be retried (they won't clear on their own) and must set the remote's needsReauth
 * flag so the UI can prompt the user to re-authenticate.
 *
 * Markers are matched case-insensitively as substrings so they catch rclone's multi-
 * line error strings (e.g. "oauth2: cannot fetch token: 401 Unauthorized").
 *
 * Bare HTTP-code substrings ("401", "403") are intentionally NOT matched because
 * rclone error output can embed byte counts or file sizes that contain those digit
 * sequences — false-positives would flag healthy remotes (the same trap
 * [classifyJobError]'s KDoc warns about). Instead, require adjacent auth context
 * (e.g. "401 unauthorized", "http 401", "error 403").
 */
fun isAuthError(err: String): Boolean {
    val lower = err.lowercase()
    val wordMarkers = listOf(
        "oauth2", "invalid_grant", "token expired", "expired token",
        "couldn't fetch token", "failed to refresh",
        "unauthorized", "permission denied", "accessdenied", "access denied",
        "invalid_client", "revoked",
    )
    val httpMarkers = listOf(
        "401 unauthorized", "http 401", "status 401", "error 401", "code 401",
        "403 forbidden", "http 403", "status 403", "error 403", "code 403",
    )
    return wordMarkers.any { it in lower } || httpMarkers.any { it in lower }
}

/**
 * Classifies a finished-job error string. Transient transport failures become
 * [VirgaError.Network] so the worker retries (a blanket [VirgaError.Rclone] never
 * retried). Everything else stays [VirgaError.Rclone] carrying the original rclone
 * text, which `toUserMessage` surfaces verbatim (e.g. "directory not found", quota,
 * or token errors). Only textual markers are matched — bare HTTP-code substrings
 * would false-match byte counts.
 */
internal fun classifyJobError(err: String): VirgaError {
    val lower = err.lowercase()
    val transient = listOf(
        "timeout", "timed out", "deadline exceeded", "connection reset",
        "connection refused", "no such host", "temporarily", "try again",
        "too many requests", "i/o timeout", "broken pipe",
    )
    return if (transient.any { it in lower }) VirgaError.Network(err) else VirgaError.Rclone(message = err)
}

/** Splits "remote:path/to/file" into ("remote:", "path/to/file"). */
internal fun splitFs(spec: String): Pair<String, String> {
    val idx = spec.indexOf(':')
    if (idx < 0) return spec to ""
    return spec.substring(0, idx + 1) to spec.substring(idx + 1)
}

internal fun JsonObjectBuilder.putConfig(config: RcloneRunConfig) {
    putJsonObject("_config") {
        put("Transfers", config.transfers)
        put("Checkers", config.checkers)
        put("BufferSize", config.bufferSize)
        if (config.dryRun) put("DryRun", true)
        if (!config.bwLimit.isNullOrBlank()) put("BwLimit", config.bwLimit)
        // WS3.1 Tier-2 options
        if (config.checksum) put("CheckSum", true)
        if (!config.backupDir.isNullOrBlank()) put("BackupDir", config.backupDir)
        config.maxDelete?.let { put("MaxDelete", it) }
        // B6: data cap — stop before exceeding the cap (CAUTIOUS mode).
        if (!config.maxTransfer.isNullOrBlank()) {
            put("MaxTransfer", config.maxTransfer)
            put("CutoffMode", "CAUTIOUS")
        }
        // Merge power-user extra config entries. The Map<String, Any> contract
        // guarantees values are Boolean, Number, or String (enforced by
        // ExtraConfigParser before this point). Applied LAST, so an explicit
        // extraConfig entry (e.g. "CheckSum=false") intentionally overrides the
        // matching typed toggle above — the raw box is the power-user escape hatch.
        config.extraConfig.forEach { (key, value) ->
            when (value) {
                is Boolean -> put(key, JsonPrimitive(value))
                is Number -> put(key, JsonPrimitive(value))
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }
}

internal fun JsonObjectBuilder.putFilters(
    filters: List<String>,
    minSize: String? = null,
    maxSize: String? = null,
    minAge: String? = null,
    maxAge: String? = null,
) {
    val hasSizeAge = !minSize.isNullOrBlank() || !maxSize.isNullOrBlank() ||
        !minAge.isNullOrBlank() || !maxAge.isNullOrBlank()
    if (filters.isEmpty() && !hasSizeAge) return
    putJsonObject("_filter") {
        if (filters.isNotEmpty()) putJsonArray("FilterRule") { filters.forEach { add(it) } }
        if (!minSize.isNullOrBlank()) put("MinSize", minSize)
        if (!maxSize.isNullOrBlank()) put("MaxSize", maxSize)
        if (!minAge.isNullOrBlank()) put("MinAge", minAge)
        if (!maxAge.isNullOrBlank()) put("MaxAge", maxAge)
    }
}
