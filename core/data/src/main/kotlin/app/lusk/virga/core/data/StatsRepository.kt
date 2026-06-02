package app.lusk.virga.core.data

import app.lusk.virga.core.common.model.LifetimeStats
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.database.dao.AppStatsDao
import app.lusk.virga.core.database.entity.AppStatsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepository @Inject constructor(
    private val appStatsDao: AppStatsDao,
) {
    /** Emits the current lifetime stats; produces empty defaults before any run is recorded. */
    val stats: Flow<LifetimeStats> = appStatsDao.observe().map { it?.toDomain() ?: LifetimeStats() }

    /**
     * Records one completed sync run and updates all lifetime counters atomically.
     *
     * [direction] determines which directional byte-bucket the transferred bytes count toward
     * (they always also count toward [LifetimeStats.totalBytesTransferred]). Streak tracking
     * is only advanced for successful runs.
     */
    suspend fun recordRun(
        direction: SyncDirection,
        bytesTransferred: Long,
        filesTransferred: Int,
        success: Boolean,
        durationMs: Long,
        finishedAtEpochMs: Long,
    ) {
        val (up, down, twoWay) = when (direction) {
            SyncDirection.UPLOAD -> Triple(bytesTransferred, 0L, 0L)
            SyncDirection.DOWNLOAD -> Triple(0L, bytesTransferred, 0L)
            SyncDirection.BISYNC -> Triple(0L, 0L, bytesTransferred)
        }
        val syncDayEpochDay = finishedAtEpochMs / 86_400_000L
        appStatsDao.record(
            successDelta = if (success) 1 else 0,
            failDelta = if (success) 0 else 1,
            files = filesTransferred.toLong(),
            bytes = bytesTransferred,
            up = up,
            down = down,
            twoWay = twoWay,
            durationMs = durationMs,
            nowEpochMs = finishedAtEpochMs,
            syncDayEpochDay = syncDayEpochDay,
            isSuccess = success,
        )
    }

    /** Clears all lifetime stats. Wired to a "reset statistics" user action. */
    suspend fun reset() = appStatsDao.clear()
}

// ---------------------------------------------------------------------------
// Internal mapper — lives in core:data alongside the other mappers in Mappers.kt
// but placed here to keep the entity-to-domain conversion co-located with the
// repository that owns it (AppStatsEntity is only consumed by StatsRepository).
// ---------------------------------------------------------------------------

private fun AppStatsEntity.toDomain() = LifetimeStats(
    firstSyncEpochMs = firstSyncEpochMs,
    totalRuns = totalRuns,
    successfulRuns = successfulRuns,
    failedRuns = failedRuns,
    totalFilesTransferred = totalFilesTransferred,
    totalBytesTransferred = totalBytesTransferred,
    bytesUploaded = bytesUploaded,
    bytesDownloaded = bytesDownloaded,
    bytesTwoWay = bytesTwoWay,
    totalSyncMillis = totalSyncMillis,
    largestRunBytes = largestRunBytes,
    longestRunMillis = longestRunMillis,
    currentStreakDays = currentStreakDays,
    longestStreakDays = longestStreakDays,
)
