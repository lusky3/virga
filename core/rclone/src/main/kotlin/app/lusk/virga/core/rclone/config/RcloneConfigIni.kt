package app.lusk.virga.core.rclone.config

/**
 * Pure (no Android deps) utility for parsing, serializing, merging, extracting, and
 * redacting rclone.conf INI files.
 *
 * Section and key order are preserved via [LinkedHashMap]. Lines before the first
 * `[section]` header (the global preamble), blank lines, and comment lines (`#`/`;`)
 * are silently ignored. Values may contain `=` — only the FIRST `=` is split on.
 * Malformed lines (no `=`) inside a section body are silently skipped.
 */
object RcloneConfigIni {

    const val REDACTED_PLACEHOLDER = "***REDACTED***"

    /**
     * Substrings (lowercase) used to identify secret keys via [isSensitiveKey].
     *
     * A key is redacted when its lowercased name CONTAINS any of these substrings.
     * This catches real rclone `IsPassword` fields across all backends without
     * requiring an exhaustive fixed list, including: `pass`, `password`, `password2`,
     * `token`, `client_secret`, `secret_access_key`, `key`, `key_file_pass`,
     * `service_account_credentials`, `sa_credentials`, `api_key`, `api_password`,
     * `secret`, `plex_password`, `file_password`, `folder_password`,
     * `mailbox_password`, `library_key`, `client_certificate_password`,
     * `otp_secret_key`, `application_credential_secret`.
     *
     * Structural keys that do NOT match: `type`, `region`, `endpoint`, `provider`,
     * `client_id`, `url`, `host`, `port`, `auth` (swift auth-endpoint URL).
     */
    private val SENSITIVE_SUBSTRINGS: List<String> = listOf(
        "pass", "secret", "token", "key", "credential",
    )

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return SENSITIVE_SUBSTRINGS.any { lower.contains(it) }
    }

    /**
     * Parses rclone.conf text into an ordered map of sectionName → ordered key→value.
     *
     * - Lines before the first `[section]` header are silently skipped.
     * - Blank lines and lines starting with `#` or `;` are ignored.
     * - Values split on the FIRST `=` only; surrounding whitespace is stripped.
     * - Lines in a section body without `=` are silently skipped.
     */
    fun parse(text: String): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val result = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var current: LinkedHashMap<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") || line.startsWith(";") -> Unit
                line.startsWith("[") && line.endsWith("]") -> {
                    val name = line.substring(1, line.length - 1).trim()
                    val section = LinkedHashMap<String, String>()
                    result[name] = section
                    current = section
                }
                current != null -> {
                    val eqIdx = line.indexOf('=')
                    if (eqIdx > 0) {
                        val k = line.substring(0, eqIdx).trim()
                        val v = line.substring(eqIdx + 1).trim()
                        current[k] = v
                    }
                }
            }
        }
        return result
    }

    /**
     * Serializes [sections] back to rclone.conf text.
     * Each section is written as `[name]\nkey = value\n...\n\n`.
     */
    fun serialize(sections: LinkedHashMap<String, LinkedHashMap<String, String>>): String {
        val sb = StringBuilder()
        for ((name, keys) in sections) {
            sb.append('[').append(name).append("]\n")
            for ((k, v) in keys) {
                sb.append(k).append(" = ").append(v).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Merges [incoming] sections into [base]. Returns a new map.
     *
     * - All sections from [base] are included first (order preserved).
     * - Sections only in [incoming] are appended.
     * - On name collision: [overwriteExisting]=true → incoming replaces base; false → base kept.
     */
    fun merge(
        base: LinkedHashMap<String, LinkedHashMap<String, String>>,
        incoming: LinkedHashMap<String, LinkedHashMap<String, String>>,
        overwriteExisting: Boolean,
    ): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val result = LinkedHashMap<String, LinkedHashMap<String, String>>()
        for ((name, section) in base) {
            result[name] = if (overwriteExisting && incoming.containsKey(name)) {
                LinkedHashMap(incoming[name]!!)
            } else {
                LinkedHashMap(section)
            }
        }
        for ((name, section) in incoming) {
            if (!result.containsKey(name)) {
                result[name] = LinkedHashMap(section)
            }
        }
        return result
    }

    /**
     * Extracts a single section from [sections] by [name].
     * Returns a map with one entry, or an empty map if [name] is not found.
     */
    fun extractSection(
        sections: LinkedHashMap<String, LinkedHashMap<String, String>>,
        name: String,
    ): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val result = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val section = sections[name] ?: return result
        result[name] = LinkedHashMap(section)
        return result
    }

    /**
     * Returns a new map where any key whose lowercased name contains a substring
     * from [SENSITIVE_SUBSTRINGS] has its value replaced with [REDACTED_PLACEHOLDER].
     * Section structure and non-sensitive keys are preserved unchanged.
     *
     * Note: importing a redacted export will write [REDACTED_PLACEHOLDER] as a literal
     * credential value. This is acceptable for skeleton/troubleshooting exports — users
     * must supply real values before a redacted config is usable.
     */
    fun redact(
        sections: LinkedHashMap<String, LinkedHashMap<String, String>>,
    ): LinkedHashMap<String, LinkedHashMap<String, String>> {
        val result = LinkedHashMap<String, LinkedHashMap<String, String>>()
        for ((name, keys) in sections) {
            val redacted = LinkedHashMap<String, String>()
            for ((k, v) in keys) {
                redacted[k] = if (isSensitiveKey(k)) REDACTED_PLACEHOLDER else v
            }
            result[name] = redacted
        }
        return result
    }

    /** Convenience: parse → redact → serialize. */
    fun redactText(text: String): String = serialize(redact(parse(text)))
}
