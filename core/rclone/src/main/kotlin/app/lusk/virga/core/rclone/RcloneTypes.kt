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
    /**
     * When true, use rclone `sync/move`: files are transferred then deleted from the
     * source. One-way only; mutually exclusive with [deleteExtraneous]. A move that
     * encounters file errors MUST NOT tolerate them — deleting the source after a
     * partial transfer risks data loss.
     */
    val deleteSource: Boolean = false,
    // WS3.1 Tier-2 options -------------------------------------------------------
    /** Rclone _config key "CheckSum": compare by hash rather than size+modtime. */
    val checksum: Boolean = false,
    /** Rclone _config key "BackupDir": move replaced/deleted files here instead of
     *  removing them. Null = unset (rclone default). */
    val backupDir: String? = null,
    /** Rclone _config key "MaxDelete": abort if more than N deletes would occur.
     *  Null = unset. */
    val maxDelete: Int? = null,
    /** Pre-validated extra _config entries (key → typed value). Empty = none.
     *  Keys are validated against [ExtraConfigParser.ALLOWLIST] before this point. */
    val extraConfig: Map<String, Any> = emptyMap(),
)

/** Options for a two-way `sync/bisync`. */
data class BisyncOptions(
    val bwLimit: String? = null,
    val transfers: Int = 4,
    val checkers: Int = 8,
    val bufferSize: String = "16M",
    val filters: List<String> = emptyList(),
    val dryRun: Boolean = false,
    /** First-run resync to establish the bisync baseline. */
    val resync: Boolean = false,
    // WS3.1 Tier-2 options -------------------------------------------------------
    /** Rclone _config key "CheckSum": compare by hash rather than size+modtime. */
    val checksum: Boolean = false,
    /** Rclone _config key "BackupDir": move replaced/deleted files here. Null = unset. */
    val backupDir: String? = null,
    /** Rclone _config key "MaxDelete" abort threshold. Null = unset. */
    val maxDelete: Int? = null,
    /** Pre-validated extra _config entries (key → typed value). Empty = none. */
    val extraConfig: Map<String, Any> = emptyMap(),
)

/** Parsed view of the rclone config (remote name -> type). */
data class RcloneConfig(
    val remotes: Map<String, String>,
)
