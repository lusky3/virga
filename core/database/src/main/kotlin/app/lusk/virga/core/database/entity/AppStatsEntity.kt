package app.lusk.virga.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton-row entity (id always 0) that accumulates lifetime sync statistics.
 * Never pruned — the row is seeded by the migration and only grows via additive
 * SQL UPDATEs in [app.lusk.virga.core.database.dao.AppStatsDao].
 */
@Entity(tableName = "app_stats")
data class AppStatsEntity(
    @PrimaryKey val id: Int = 0,
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
    /** Epoch-day of the last successful sync (LocalDate.toEpochDay()), used for streak tracking. */
    val lastSyncDayEpochDay: Long = 0,
)
