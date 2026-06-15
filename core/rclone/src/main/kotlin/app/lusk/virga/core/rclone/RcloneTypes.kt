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

/**
 * The rclone `_config` inputs shared by one-way sync and bisync. Letting both
 * option types expose this view keeps [putConfig] a single-parameter function
 * (the config block has ~10 knobs; passing them individually would blow past the
 * function parameter-count limit — data-class constructors are exempt, plain
 * functions are not).
 */
internal interface RcloneRunConfig {
    val bwLimit: String?
    val transfers: Int
    val checkers: Int
    val bufferSize: String
    val dryRun: Boolean
    val checksum: Boolean
    val backupDir: String?
    val maxDelete: Int?
    val maxTransfer: String?
    val extraConfig: Map<String, Any>
}

/** Options for a one-way sync (`sync/copy`, `sync/sync`). */
data class SyncOptions(
    val direction: SyncDirection,
    override val bwLimit: String? = null,
    override val transfers: Int = 4,
    override val checkers: Int = 8,
    override val bufferSize: String = "16M",
    val filters: List<String> = emptyList(),
    /** rclone _filter key "MinSize" (SizeSuffix, e.g. "10M"); null = unset. */
    val minSize: String? = null,
    /** rclone _filter key "MaxSize" (SizeSuffix, e.g. "1G"); null = unset. */
    val maxSize: String? = null,
    /** rclone _filter key "MinAge" (Duration, e.g. "30d", "1h"); null = unset. */
    val minAge: String? = null,
    /** rclone _filter key "MaxAge" (Duration, e.g. "7d"); null = unset. */
    val maxAge: String? = null,
    override val dryRun: Boolean = false,
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
    override val checksum: Boolean = false,
    /** Rclone _config key "BackupDir": move replaced/deleted files here instead of
     *  removing them. Null = unset (rclone default). */
    override val backupDir: String? = null,
    /** Rclone _config key "MaxDelete": abort if more than N deletes would occur.
     *  Null = unset. */
    override val maxDelete: Int? = null,
    /** Pre-validated extra _config entries (key → typed value). Empty = none.
     *  Keys are validated against [ExtraConfigParser.ALLOWLIST] before this point. */
    override val extraConfig: Map<String, Any> = emptyMap(),
    /** Rclone _config key "MaxTransfer" (SizeSuffix, e.g. "10G"). When set, rclone stops
     *  the run once this many bytes have been transferred. CutoffMode is set to CAUTIOUS
     *  automatically. Null = unset. */
    override val maxTransfer: String? = null,
) : RcloneRunConfig

/** Options for a two-way `sync/bisync`. */
data class BisyncOptions(
    override val bwLimit: String? = null,
    override val transfers: Int = 4,
    override val checkers: Int = 8,
    override val bufferSize: String = "16M",
    val filters: List<String> = emptyList(),
    /** rclone _filter key "MinSize" (SizeSuffix, e.g. "10M"); null = unset. */
    val minSize: String? = null,
    /** rclone _filter key "MaxSize" (SizeSuffix, e.g. "1G"); null = unset. */
    val maxSize: String? = null,
    /** rclone _filter key "MinAge" (Duration, e.g. "30d", "1h"); null = unset. */
    val minAge: String? = null,
    /** rclone _filter key "MaxAge" (Duration, e.g. "7d"); null = unset. */
    val maxAge: String? = null,
    override val dryRun: Boolean = false,
    /** First-run resync to establish the bisync baseline. */
    val resync: Boolean = false,
    // WS3.1 Tier-2 options -------------------------------------------------------
    /** Rclone _config key "CheckSum": compare by hash rather than size+modtime. */
    override val checksum: Boolean = false,
    /** Rclone _config key "BackupDir": move replaced/deleted files here. Null = unset. */
    override val backupDir: String? = null,
    /** Rclone _config key "MaxDelete" abort threshold. Null = unset. */
    override val maxDelete: Int? = null,
    /** Pre-validated extra _config entries (key → typed value). Empty = none. */
    override val extraConfig: Map<String, Any> = emptyMap(),
    /** Rclone _config key "MaxTransfer" (SizeSuffix, e.g. "10G"). When set, rclone stops
     *  the run once this many bytes have been transferred. CutoffMode is set to CAUTIOUS
     *  automatically. Null = unset. */
    override val maxTransfer: String? = null,
) : RcloneRunConfig

/** Parsed view of the rclone config (remote name -> type). */
data class RcloneConfig(
    val remotes: Map<String, String>,
)
