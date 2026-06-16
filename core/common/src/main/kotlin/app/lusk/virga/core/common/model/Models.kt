package app.lusk.virga.core.common.model

/** Direction of a sync task. BISYNC is rclone's two-way sync (experimental). */
enum class SyncDirection { UPLOAD, DOWNLOAD, BISYNC }

/** Lifecycle state of a sync task / run, surfaced in list UIs. */
enum class SyncStatus { IDLE, QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED }

/** A configured rclone remote (e.g. a Google Drive account). */
data class Remote(
    val name: String,
    val type: String,
    /** True when the stored OAuth token has expired or been revoked; the user must re-authenticate. */
    val needsReauth: Boolean = false,
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
    /** rclone --min-size / --max-size (SizeSuffix, e.g. "10M", "1.5G"); blank = unset. */
    val minSize: String = "",
    val maxSize: String = "",
    /** rclone --min-age / --max-age (Duration, e.g. "30d", "1h"); blank = unset. */
    val minAge: String = "",
    val maxAge: String = "",
    /** rclone --bwlimit on WiFi/metered; null/blank = no limit. e.g. "1M" or a timetable. */
    val bwLimitWifi: String? = null,
    val bwLimitMetered: String? = null,
    /** rclone _config key "MaxTransfer" (SizeSuffix, e.g. "10G"); blank = unset. When set,
     *  rclone stops the run when this many bytes have been transferred. CutoffMode is set
     *  to CAUTIOUS so the cap is never exceeded. */
    val maxTransfer: String = "",
    val transfers: Int = 4,
    val checkers: Int = 8,
    val bufferSize: String = "16M",
    val deleteExtraneous: Boolean = false,
    /**
     * rclone `sync/move` — deletes source after a successful transfer; one-way only.
     * Mutually exclusive with [deleteExtraneous]: enabling one forces the other off.
     */
    val deleteSource: Boolean = false,
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
    // B8: configurable retry ---------------------------------------------------
    /**
     * Maximum total WorkManager attempts. runAttemptCount is 0-based; retrying when
     * runAttemptCount < maxRetries - 1 yields exactly [maxRetries] total tries.
     * Default 3 caps the previously-unbounded Network retry.
     */
    val maxRetries: Int = 3,
    /** When true, non-auth VirgaError.Rclone failures also retry (up to [maxRetries]). */
    val retryOnRclone: Boolean = false,
    /** Initial backoff delay in seconds for WorkManager setBackoffCriteria. */
    val backoffSeconds: Long = 30,
    /** EXPONENTIAL backoff when true; LINEAR when false. */
    val backoffExponential: Boolean = true,
    // B4: multi-time calendar schedule -----------------------------------------
    /**
     * Minutes-of-day (0..1439) for the calendar schedule. When non-empty,
     * overrides the single [scheduleHour]/[scheduleMinute] pair; each element
     * is tried independently and the soonest qualifying occurrence is scheduled.
     * Empty = single-time fallback (behavior-preserving default).
     */
    val scheduleTimes: List<Int> = emptyList(),
    // B10: sync-all concurrency / order / groups --------------------------------
    /**
     * Optional group label. Tasks with the same non-blank groupTag are enqueued
     * and can be cancelled together via [SyncScheduler.syncAll]/[cancelSyncAll].
     * Empty = no group (participates in global "sync all" only).
     */
    val groupTag: String = "",
    /**
     * Best-effort ordering hint for syncAll: tasks are sorted (sortOrder ASC, id ASC)
     * and ENQUEUED in that order. Each task is its own WorkManager unique work, so this
     * controls enqueue order, not a hard execution-order guarantee (WorkManager may run
     * independent work concurrently). Lower values enqueue first; ties broken by id.
     */
    val sortOrder: Int = 0,
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
    /**
     * Newline-joined list of per-file failures from the last error run. Each line is
     * "path\terror" (tab-separated). Empty when there are no file-level failures.
     * Capped at 100 entries at capture time to avoid unbounded storage.
     */
    val failedFiles: String = "",
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

/**
 * Lifetime sync statistics for the app. Mirrors [AppStatsEntity] as a clean
 * domain type. All counters default to 0/null so callers get sensible empty
 * state before the first run is recorded.
 */
data class LifetimeStats(
    val firstSyncEpochMs: Long? = null,
    val totalRuns: Long = 0,
    val successfulRuns: Long = 0,
    val failedRuns: Long = 0,
    val totalFilesTransferred: Long = 0,
    val totalBytesTransferred: Long = 0,
    val bytesUploaded: Long = 0,
    val bytesDownloaded: Long = 0,
    val bytesTwoWay: Long = 0,
    val totalSyncMillis: Long = 0,
    val largestRunBytes: Long = 0,
    val longestRunMillis: Long = 0,
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
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
    /** Files deleted (mirror / delete-extraneous). In `--dry-run` this is the
     *  count rclone *would* delete, surfaced in the preview's blast radius. */
    val deletes: Int = 0,
    /** rclone stats group ("job/<id>") for this run, stamped on the terminal
     *  emission. Lets the worker scope a `core/transferred` failure query to THIS
     *  run rather than the whole shared daemon — concurrent "sync all" runs would
     *  otherwise cross-contaminate each other's failed-file lists. Null on
     *  non-terminal and dry-run emissions. */
    val statsGroup: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}
