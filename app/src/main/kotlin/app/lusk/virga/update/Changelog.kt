package app.lusk.virga.update

/** A single release's version name and human-readable notes. */
data class ReleaseNotes(val versionName: String, val notes: List<String>)

/**
 * In-app changelog. Each entry maps a version name to a short list of
 * user-facing notes (BRAND §2 voice: calm, informative). Newest first.
 */
val RELEASE_NOTES: List<ReleaseNotes> = listOf(
    ReleaseNotes(
        versionName = "0.1.0",
        notes = listOf(
            "New Home dashboard with sync status and lifetime stats",
            "Refreshed app icon, provider marks, and Settings",
            "More reliable encrypted-config handling and a keyboard-free launch",
        ),
    ),
)

/** Returns the [ReleaseNotes] for [versionName], or null if not found. */
fun releaseNotesFor(versionName: String): ReleaseNotes? =
    RELEASE_NOTES.firstOrNull { it.versionName == versionName }
