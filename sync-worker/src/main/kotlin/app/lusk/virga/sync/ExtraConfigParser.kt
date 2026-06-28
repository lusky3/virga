package app.lusk.virga.sync

import java.util.logging.Logger

/**
 * Validates and parses the [SyncTask.extraConfig] newline-separated "Key=Value"
 * passthrough field into a typed [Map] suitable for the rclone RC `_config` block.
 *
 * Only keys on [ALLOWLIST] are accepted. Values are coerced to:
 *   - Boolean when the string is exactly "true" or "false" (case-insensitive)
 *   - Int/Long when the string looks like an integer
 *   - String otherwise
 *
 * DEFERRED dedicated typed toggles reachable via this passthrough using their
 * allowlisted keys: TrackRenames, SizeOnly, ConflictResolve, MaxTransfer, OrderBy.
 * For flaky sources, MaxDuration (e.g. MaxDuration=10m) and CutoffMode (e.g.
 * CutoffMode=HARD) wall-clock-cap a run without dedicated UI.
 */
object ExtraConfigParser {

    private val log = Logger.getLogger("ExtraConfigParser")

    /**
     * Known-safe rclone _config keys accepted by the raw passthrough. Any key not
     * in this set is rejected (editor validation) or dropped with a warning
     * (executor defence-in-depth).
     */
    val ALLOWLIST: Set<String> = setOf(
        "CheckSum",
        "SizeOnly",
        "TrackRenames",
        "BackupDir",
        "Suffix",
        "MaxDelete",
        "MaxTransfer",
        "OrderBy",
        "IgnoreExisting",
        "IgnoreSize",
        "NoTraverse",
        "MaxDuration",
        "CutoffMode",
    )

    /**
     * Result of parsing a single "Key=Value" line.
     * [ParseResult.Ok] carries the typed entry; [ParseResult.UnknownKey] and
     * [ParseResult.Malformed] carry a human-readable message for UI display.
     */
    sealed interface ParseResult {
        data class Ok(val key: String, val value: Any) : ParseResult
        data class UnknownKey(val key: String, val message: String) : ParseResult
        data class Malformed(val line: String, val message: String) : ParseResult
    }

    /** Validates a single "Key=Value" line without executing it. */
    fun validateLine(line: String): ParseResult {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return ParseResult.Ok("", "")
        val eq = trimmed.indexOf('=')
        if (eq <= 0) return ParseResult.Malformed(line, "Expected \"Key=Value\", got: \"$trimmed\"")
        val key = trimmed.substring(0, eq).trim()
        val raw = trimmed.substring(eq + 1).trim()
        if (key !in ALLOWLIST) {
            return ParseResult.UnknownKey(
                key,
                "\"$key\" is not an allowlisted rclone config key. " +
                    "Allowed: ${ALLOWLIST.sorted().joinToString(", ")}",
            )
        }
        return ParseResult.Ok(key, coerceValue(raw))
    }

    /**
     * Parses the full [extraConfig] block into a typed map, silently dropping
     * unknown or malformed lines with a log warning (defence-in-depth; the editor
     * validates before save so bad input should never reach here in normal flow).
     */
    fun parseToMap(extraConfig: String): Map<String, Any> {
        if (extraConfig.isBlank()) return emptyMap()
        val result = mutableMapOf<String, Any>()
        extraConfig.lines().forEach { line ->
            when (val r = validateLine(line)) {
                is ParseResult.Ok -> if (r.key.isNotEmpty()) result[r.key] = r.value
                is ParseResult.UnknownKey -> log.warning("extraConfig: dropping unknown key \"${r.key}\"")
                is ParseResult.Malformed -> log.warning("extraConfig: dropping malformed line \"${r.line}\"")
            }
        }
        return result
    }

    /**
     * Returns a human-readable error for the first invalid line in [text], or
     * null if every non-blank line is well-formed and allowlisted.
     */
    fun firstError(text: String): String? {
        text.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            when (val r = validateLine(line)) {
                is ParseResult.Ok -> Unit
                is ParseResult.UnknownKey -> return r.message
                is ParseResult.Malformed -> return r.message
            }
        }
        return null
    }

    private fun coerceValue(raw: String): Any {
        if (raw.equals("true", ignoreCase = true)) return true
        if (raw.equals("false", ignoreCase = true)) return false
        raw.toIntOrNull()?.let { return it }
        raw.toLongOrNull()?.let { return it }
        return raw
    }
}
