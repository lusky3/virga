package app.lusk.virga.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.lusk.virga.core.database.entity.AppStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppStatsDao {

    @Query("SELECT * FROM app_stats WHERE id = 0")
    fun observe(): Flow<AppStatsEntity?>

    /** Seeds the singleton row if it does not exist yet. Safe to call repeatedly. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureRow(row: AppStatsEntity = AppStatsEntity())

    /**
     * Atomically increments all additive counters for a completed run.
     * SQLite serialises writes, so concurrent workers cannot interleave partial
     * updates — each caller's delta is applied atomically in one statement.
     *
     * [firstSyncCandidate] is null for a failed run and the finish timestamp for a
     * successful one, so "first sync" (the "Syncing since …" anchor) is set once by the
     * first *successful* run — consistent with the success-only streak. COALESCE keeps
     * the existing value when the candidate is null.
     */
    @Query(
        """
        UPDATE app_stats SET
            totalRuns               = totalRuns + 1,
            successfulRuns          = successfulRuns + :successDelta,
            failedRuns              = failedRuns + :failDelta,
            totalFilesTransferred   = totalFilesTransferred + :files,
            totalBytesTransferred   = totalBytesTransferred + :bytes,
            bytesUploaded           = bytesUploaded + :up,
            bytesDownloaded         = bytesDownloaded + :down,
            bytesTwoWay             = bytesTwoWay + :twoWay,
            totalSyncMillis         = totalSyncMillis + :durationMs,
            largestRunBytes         = MAX(largestRunBytes, :bytes),
            longestRunMillis        = MAX(longestRunMillis, :durationMs),
            firstSyncEpochMs        = COALESCE(firstSyncEpochMs, :firstSyncCandidate)
        WHERE id = 0
        """,
    )
    suspend fun applyAdditive(
        successDelta: Int,
        failDelta: Int,
        files: Long,
        bytes: Long,
        up: Long,
        down: Long,
        twoWay: Long,
        durationMs: Long,
        firstSyncCandidate: Long?,
    )

    @Query(
        "UPDATE app_stats SET currentStreakDays = :current, longestStreakDays = :longest, " +
            "lastSyncDayEpochDay = :day WHERE id = 0",
    )
    suspend fun applyStreak(current: Int, longest: Int, day: Long)

    @Query("SELECT * FROM app_stats WHERE id = 0")
    suspend fun getOnce(): AppStatsEntity?

    /**
     * Single transactional entry point: ensures the row exists, applies additive
     * counters, and (on success) recalculates the streak. All three operations
     * commit or roll back together.
     *
     * [syncDayEpochDay] is `finishedAtEpochMs / 86_400_000L` — the caller
     * computes it once and passes it in so the DAO stays free of clock calls.
     */
    @Transaction
    suspend fun record(
        successDelta: Int,
        failDelta: Int,
        files: Long,
        bytes: Long,
        up: Long,
        down: Long,
        twoWay: Long,
        durationMs: Long,
        nowEpochMs: Long,
        syncDayEpochDay: Long,
        isSuccess: Boolean,
    ) {
        ensureRow()
        // "First sync" anchors to the first SUCCESSFUL run, so a failed first attempt
        // doesn't set the "Syncing since …" date (parity with the streak below).
        applyAdditive(
            successDelta, failDelta, files, bytes, up, down, twoWay, durationMs,
            firstSyncCandidate = if (isSuccess) nowEpochMs else null,
        )
        if (isSuccess) {
            val s = getOnce() ?: return
            val last = s.lastSyncDayEpochDay
            val current = when {
                last == 0L -> 1
                syncDayEpochDay == last -> s.currentStreakDays.coerceAtLeast(1)
                syncDayEpochDay == last + 1 -> s.currentStreakDays + 1
                syncDayEpochDay > last -> 1   // gap → streak resets
                else -> return                // out-of-order / older run: nothing to update
            }
            applyStreak(current, maxOf(current, s.longestStreakDays), maxOf(syncDayEpochDay, last))
        }
    }

    /** Clears the stats row — wired to a "reset statistics" user action. */
    @Query("DELETE FROM app_stats")
    suspend fun clear()
}
