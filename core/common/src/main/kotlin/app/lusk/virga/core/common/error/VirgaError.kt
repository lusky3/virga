package app.lusk.virga.core.common.error

/**
 * Domain error hierarchy surfaced to the UI. Lower layers map raw failures
 * (rclone exit codes, IO exceptions, HTTP errors) onto these so the UI can show
 * actionable messages without knowing about rclone internals.
 */
sealed class VirgaError(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    /** No connectivity, timeouts, DNS, or transient transport failure. */
    class Network(message: String, cause: Throwable? = null) : VirgaError(message, cause)

    /** OAuth token expired/revoked; the user must re-authenticate the remote. */
    class Auth(val remote: String, message: String, cause: Throwable? = null) :
        VirgaError(message, cause)

    /** Local or remote storage problems: full disk, missing permission, gone volume. */
    class Storage(message: String, cause: Throwable? = null) : VirgaError(message, cause)

    /** The rclone daemon/process failed: crash, non-zero exit, bad RC response. */
    class Rclone(val exitCode: Int? = null, message: String, cause: Throwable? = null) :
        VirgaError(message, cause)

    /** An in-flight transfer made zero progress past the stall window — typically a
     *  source read wedged on a failing disk/SD card. [file] is the file rclone was
     *  reading when it stalled, when known. Non-retryable: re-running hammers the same
     *  unreadable region. */
    class Stall(val file: String? = null, message: String, cause: Throwable? = null) :
        VirgaError(message, cause)

    /** A sync surfaced a conflict that policy could not resolve automatically. */
    class Conflict(message: String, cause: Throwable? = null) : VirgaError(message, cause)

    /** Anything we could not classify. */
    class Unknown(message: String, cause: Throwable? = null) : VirgaError(message, cause)
}
