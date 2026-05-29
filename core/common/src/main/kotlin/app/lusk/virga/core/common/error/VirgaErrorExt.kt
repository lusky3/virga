package app.lusk.virga.core.common.error

/**
 * Maps a [VirgaError] to a human-readable, actionable message for display in the
 * UI. Centralizing this mapping prevents duplicated ad-hoc `e.message` calls
 * across feature modules.
 */
fun VirgaError.toUserMessage(): String = when (this) {
    is VirgaError.Network ->
        "No internet connection. Check your network and retry."
    is VirgaError.Auth ->
        "Sign-in expired for \"$remote\". Re-add the remote to reconnect."
    is VirgaError.Storage ->
        "Storage error: ${message.ifBlank { "check available space and permissions" }}"
    is VirgaError.Rclone ->
        exitCode?.let { "Sync engine error (code $it). Try again." }
            ?: "Sync engine error. Try again."
    is VirgaError.Conflict ->
        "Conflict detected. Open the Conflicts screen to resolve."
    is VirgaError.Unknown ->
        message.ifBlank { "Something went wrong. Tap to retry." }
}

/**
 * Returns a user-facing message for any [Throwable]: maps [VirgaError] cases
 * to friendly copy, and falls back to a generic message for unknown exceptions.
 */
fun Throwable.toUserMessage(): String =
    (this as? VirgaError)?.toUserMessage()
        ?: message?.ifBlank { null }
        ?: "Something went wrong. Tap to retry."
