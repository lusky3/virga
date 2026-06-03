package app.lusk.virga.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus

/**
 * Cached metadata for a configured rclone remote. The authoritative config
 * lives in the encrypted rclone.conf; this row exists for fast list rendering
 * without spinning up the daemon.
 */
@Entity(tableName = "remotes")
data class RemoteEntity(
    @PrimaryKey val name: String,
    val type: String,
    val displayName: String,
)

/** A user-defined sync job. rclone owns per-file state; this owns the config. */
@Entity(tableName = "sync_tasks")
data class SyncTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourcePath: String,
    val remoteName: String,
    val remotePath: String,
    val direction: SyncDirection,
    /**
     * Polling interval in minutes; null means manual-only. Minimum enforced at 15.
     * Mutually exclusive with the calendar schedule ([scheduleDaysMask] != 0).
     */
    val intervalMinutes: Int?,
    /**
     * Calendar schedule: bitmask of weekdays to run on, where bit (DayOfWeek.value
     * - 1) is set (Mon=bit0 … Sun=bit6). 0 means no calendar schedule (use
     * [intervalMinutes] or manual). When non-zero, the task runs at
     * [scheduleHour]:[scheduleMinute] local time on each selected day.
     */
    val scheduleDaysMask: Int = 0,
    val scheduleHour: Int = 9,
    val scheduleMinute: Int = 0,
    /** Newline-joined include/exclude glob patterns. */
    val filters: String = "",
    /** rclone --bwlimit on WiFi/metered; null/blank = no limit. e.g. "1M". */
    val bwLimitWifi: String? = null,
    val bwLimitMetered: String? = null,
    val transfers: Int = 4,
    val checkers: Int = 8,
    val bufferSize: String = "16M",
    val deleteExtraneous: Boolean = false,
    val wifiOnly: Boolean = true,
    val requiresCharging: Boolean = false,
    val enabled: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    // WS3.1 Tier-2 rclone options -----------------------------------------------
    /** Compare by hash rather than size+modtime (rclone _config key "CheckSum"). */
    val checksum: Boolean = false,
    /**
     * Rclone _config key "BackupDir". When set, files deleted or replaced at the
     * destination are moved here rather than removed — a safety net for Mirror/sync.
     * Null/blank = unset (rclone default: files are deleted outright).
     */
    val backupDir: String? = null,
    /**
     * Rclone _config key "MaxDelete". Aborts a mirror/sync run if more than N files
     * would be deleted, guarding against accidental mass-delete. Null = unset.
     */
    val maxDelete: Int? = null,
    /**
     * Newline-separated "Key=Value" pairs merged into the RC _config block at run
     * time (power-user passthrough). Validated against an allowlist before persisting
     * and again at execution. Unknown keys are dropped with a warning at execution
     * time as a defence-in-depth measure.
     *
     * DEFERRED dedicated typed toggles (reachable via this field using their
     * allowlisted keys): TrackRenames, SizeOnly, ConflictResolve, MaxTransfer,
     * OrderBy.
     */
    val extraConfig: String = "",
)

/**
 * A bisync conflict detected on the destination: two variants of the same
 * logical file, kept by rclone with `--conflict-suffix` (default `conflict1`/
 * `conflict2`). The user resolves it by picking which variant to keep.
 */
@Entity(
    tableName = "conflicts",
    foreignKeys = [
        ForeignKey(
            entity = SyncTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // taskId is part of the unique key so two tasks targeting overlapping subtrees
    // of the same remote keep independent conflict rows (a base path can otherwise
    // ping-pong ownership between tasks). The composite's leftmost column also
    // indexes the FK, so no separate Index("taskId") is needed.
    indices = [Index(value = ["taskId", "remoteName", "basePath"], unique = true)],
)
data class ConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val remoteName: String,
    /** Base file path without the conflict suffix (e.g. "Docs/report.txt"). */
    val basePath: String,
    /** Path to the variant rclone kept on side 1 (e.g. "Docs/report.txt.conflict1"). */
    val variant1Path: String,
    /** Path to the variant rclone kept on side 2 (e.g. "Docs/report.txt.conflict2"). */
    val variant2Path: String,
    val variant1Size: Long,
    val variant2Size: Long,
    val detectedAtEpochMs: Long = System.currentTimeMillis(),
    val resolved: Boolean = false,
)

/** A single execution of a [SyncTaskEntity]. */
@Entity(
    tableName = "sync_runs",
    foreignKeys = [
        ForeignKey(
            entity = SyncTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val status: SyncStatus,
    val filesTransferred: Int = 0,
    val bytesTransferred: Long = 0,
    val errorCount: Int = 0,
    val errorMessage: String? = null,
    /** Path to the captured rclone verbose log for this run, if any. */
    val logPath: String? = null,
)
