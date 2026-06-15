package app.lusk.virga.update

/** A single release's version name and human-readable notes. */
data class ReleaseNotes(val versionName: String, val notes: List<String>)

/**
 * In-app changelog. Each entry maps a version name to a short list of
 * user-facing notes (BRAND §2 voice: calm, informative). Newest first.
 */
val RELEASE_NOTES: List<ReleaseNotes> = listOf(
    ReleaseNotes(
        versionName = "0.3.0",
        notes = listOf(
            "Create a new destination folder right from the folder picker",
            "Test a remote's connectivity on demand from its card menu",
            "Importing a config now warns you before it replaces your remotes",
            "Old per-run sync logs are pruned automatically, so they no longer grow without bound",
        ),
    ),
    ReleaseNotes(
        versionName = "0.2.0",
        notes = listOf(
            "Configure any rclone provider — Box, Dropbox, OneDrive, Google Drive, pCloud, and more",
            "Sign in with OAuth, or bring your own credentials",
            "Daemon-mediated OAuth for providers without a bundled sign-in",
            "Add crypt and wrapper remotes (union, alias, and others)",
            "Import and export your rclone config",
            "Backups now continue past unreadable files and report an error summary instead of stopping",
        ),
    ),
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
