package app.lusk.virga.core.common.util

/**
 * Defensive redaction of strings that may carry data Virga keeps off-device — used by
 * the per-run log writer and the (opt-in) crash reporter so neither persists nor
 * transmits secrets/paths. Centralized here so the patterns can't drift between callers.
 *
 *  - [secrets] collapses `token`/`access_token`/`refresh_token`/`client_secret`/
 *    `password` `=`/`:` values to a marker. Used by the run-log writer.
 *  - [secretsAndPaths] additionally rewrites absolute filesystem paths and SAF
 *    `content://` URIs (which can encode local folder names / remote identifiers) to
 *    `<path>`. Used by the crash reporter.
 */
object Redaction {
    // Optional surrounding quotes catch the JSON form `"access_token":"ya29..."` —
    // rclone stores tokens as JSON and error strings embed it, so the closing quote
    // sits between the keyword and the `:` and would otherwise defeat a bare `key:`.
    private val SECRET = Regex(
        "(?i)[\"']?(token|access_token|refresh_token|client_secret|password)[\"']?\\s*[=:]\\s*\\S+",
    )
    private val PATH = Regex("""(content://|/storage/|/data/|/sdcard/)\S*""")

    fun secrets(text: String): String =
        SECRET.replace(text) { "${it.groupValues[1]}=<redacted>" }

    fun secretsAndPaths(text: String): String =
        PATH.replace(secrets(text), "<path>")
}
