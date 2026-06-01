package app.lusk.virga.core.common.model

/** Direction of a sync task. BISYNC is rclone's two-way sync (experimental). */
enum class SyncDirection { UPLOAD, DOWNLOAD, BISYNC }

/** Lifecycle state of a sync task / run, surfaced in list UIs. */
enum class SyncStatus { IDLE, QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED }

/** A configured rclone remote (e.g. a Google Drive account). */
data class Remote(
    val name: String,
    val type: String,
)

/** A single entry returned by `operations/list` on a remote path. */
data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modTimeEpochMs: Long?,
    val mimeType: String? = null,
)

/**
 * A user-defined sync job. Domain model surfaced to ViewModels/UI; the Room
 * `SyncTaskEntity` is the persistence form and never leaves the data layer.
 * Fields mirror the entity (including [filters] as a newline-joined glob string).
 */
data class SyncTask(
    val id: Long = 0,
    val name: String,
    val sourcePath: String,
    val remoteName: String,
    val remotePath: String,
    val direction: SyncDirection,
    /** Polling interval in minutes; null means manual-only. */
    val intervalMinutes: Int?,
    /**
     * Calendar schedule: bitmask of weekdays (Mon=bit0 … Sun=bit6). 0 = none.
     * When non-zero, the task runs at [scheduleHour]:[scheduleMinute] local time
     * on each selected day (takes precedence over [intervalMinutes]).
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
    val createdAtEpochMs: Long = 0,
    // WS3.1 Tier-2 rclone options -----------------------------------------------
    /** Compare by hash rather than size+modtime (rclone _config key "CheckSum"). */
    val checksum: Boolean = false,
    /** Rclone _config key "BackupDir"; null = unset. */
    val backupDir: String? = null,
    /** Rclone _config key "MaxDelete" abort threshold; null = unset. */
    val maxDelete: Int? = null,
    /**
     * Newline-separated "Key=Value" pairs for the rclone _config block.
     * Must be validated against the allowlist (see ExtraConfigParser) before use.
     */
    val extraConfig: String = "",
)

/** A single execution of a [SyncTask]. Domain model; mirrors `SyncRunEntity`. */
data class SyncRun(
    val id: Long = 0,
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

/**
 * A bisync conflict: two variants of the same logical file kept by rclone.
 * Domain model; mirrors `ConflictEntity`.
 */
data class Conflict(
    val id: Long = 0,
    val taskId: Long,
    val remoteName: String,
    /** Base file path without the conflict suffix (e.g. "Docs/report.txt"). */
    val basePath: String,
    val variant1Path: String,
    val variant2Path: String,
    val variant1Size: Long,
    val variant2Size: Long,
    val detectedAtEpochMs: Long = 0,
    val resolved: Boolean = false,
)

/**
 * A single configurable option for an rclone backend, as returned by the
 * `config/providers` RC endpoint.
 *
 * [type] is the rclone type string: "string", "bool", "int", "SizeSuffix",
 * "Duration", etc.  [examples] are (value, help) pairs rclone suggests;
 * the list may be empty.
 */
data class RemoteOption(
    val name: String,
    val help: String,
    val type: String,
    val required: Boolean,
    val isPassword: Boolean,
    val default: String?,
    val examples: List<Pair<String, String>>,
    val advanced: Boolean,
)

/** Metadata for one rclone backend provider, as returned by `config/providers`. */
data class RemoteProvider(
    val name: String,
    val description: String,
    val options: List<RemoteOption>,
)

/**
 * Storage quota for a remote, fetched from rclone `operations/about`.
 * Any field may be null — backends that don't support about return partial data.
 */
data class RemoteQuota(
    val total: Long?,
    val used: Long?,
    val free: Long?,
)

/** Aggregate progress for a running sync, derived from rclone `core/stats`. */
data class SyncProgress(
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Double,
    val transferredFiles: Int,
    val totalFiles: Int,
    val etaSeconds: Long?,
    val errors: Int,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}
