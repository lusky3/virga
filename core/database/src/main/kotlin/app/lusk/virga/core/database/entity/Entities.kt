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
    /** Polling interval in minutes; null means manual-only. Minimum enforced at 15. */
    val intervalMinutes: Int?,
    /** Newline-joined include/exclude glob patterns. */
    val filters: String = "",
    /** rclone --bwlimit on WiFi/metered; null/blank = no limit. e.g. "1M". */
    val bwLimitWifi: String? = null,
    val bwLimitMetered: String? = null,
    val transfers: Int = 4,
    val checkers: Int = 8,
    val bufferSize: String = "16M",
    val wifiOnly: Boolean = true,
    val requiresCharging: Boolean = false,
    val enabled: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
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
