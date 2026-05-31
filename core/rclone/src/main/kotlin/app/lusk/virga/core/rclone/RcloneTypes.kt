package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.model.SyncDirection
import java.io.File

/** A running rclone RC daemon: child process + the localhost endpoint + creds. */
data class RcloneDaemon(
    val process: Process,
    val port: Int,
    val user: String,
    val pass: String,
    /**
     * The htpasswd file backing `--rc-htpasswd`. rclone re-reads this file on
     * every request, so it must live for the daemon's whole lifetime; it is
     * deleted when the daemon stops. Null only for daemons created in tests.
     */
    val htpasswdFile: File? = null,
) {
    val baseUrl: String get() = "http://127.0.0.1:$port"
}

/** Options for a one-way sync (`sync/copy`, `sync/sync`). */
data class SyncOptions(
    val direction: SyncDirection,
    val bwLimit: String? = null,
    val transfers: Int = 4,
    val checkers: Int = 8,
    val bufferSize: String = "16M",
    val filters: List<String> = emptyList(),
    val dryRun: Boolean = false,
    /**
     * When true, delete files on the destination that are absent from the source
     * (rclone `sync`/mirror); when false, only add and update (rclone `copy`).
     *
     * Defaults to **false** (additive copy): a one-way upload/download must never
     * silently delete files the user didn't touch. A mirror would make the
     * destination identical to the source — e.g. uploading 2 local files to a
     * remote folder holding 467 others would delete those 467. Mirroring is a
     * destructive, explicit opt-in, not a default.
     */
    val deleteExtraneous: Boolean = false,
)

/** Options for a two-way `sync/bisync`. */
data class BisyncOptions(
    val bwLimit: String? = null,
    val transfers: Int = 4,
    val checkers: Int = 8,
    val filters: List<String> = emptyList(),
    val dryRun: Boolean = false,
    /** First-run resync to establish the bisync baseline. */
    val resync: Boolean = false,
)

/** Parsed view of the rclone config (remote name -> type). */
data class RcloneConfig(
    val remotes: Map<String, String>,
)
