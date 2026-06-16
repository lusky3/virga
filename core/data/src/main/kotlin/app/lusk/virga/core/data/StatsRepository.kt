package app.lusk.virga.core.data

import app.lusk.virga.core.common.model.LifetimeStats
import app.lusk.virga.core.common.model.RemoteStat
import app.lusk.virga.core.common.model.RemoteQuotaState
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.TaskStat
import app.lusk.virga.core.common.model.TrendDay
import app.lusk.virga.core.database.dao.AppStatsDao
import app.lusk.virga.core.database.dao.SyncRunDao
import app.lusk.virga.core.database.entity.AppStatsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepository @Inject constructor(
    private val appStatsDao: AppStatsDao,
    private val syncRunDao: SyncRunDao,
    private val remoteRepository: RemoteRepository,
) {
    /** Emits the current lifetime stats; produces empty defaults before any run is recorded. */
    val stats: Flow<LifetimeStats> = appStatsDao.observe().map { it?.toDomain() ?: LifetimeStats() }

    /** Emits per-remote aggregate stats from sync_runs. */
    val remoteStats: Flow<List<RemoteStat>> = syncRunDao.observeRemoteStats().map { rows ->
        rows.map { RemoteStat(it.remoteName, it.totalRuns, it.successRuns, it.bytes, it.files) }
    }

    /** Emits per-task aggregate stats from sync_runs. */
    val taskStats: Flow<List<TaskStat>> = syncRunDao.observeTaskStats().map { rows ->
        rows.map { TaskStat(it.taskId, it.totalRuns, it.successRuns, it.bytes, it.files) }
    }

    /** Emits daily byte buckets for the trailing [days] days. */
    fun trendFlow(days: Int = 30): Flow<List<TrendDay>> {
        val sinceMs = System.currentTimeMillis() - days * 86_400_000L
        return syncRunDao.observeDailyBuckets(sinceMs).map { rows ->
            rows.map { TrendDay(dayOffset = it.day.toInt(), bytes = it.bytes) }
        }
    }

    /** Fetches quota for the named remotes (best-effort; failures yield null quota fields). */
    suspend fun fetchQuota(remoteNames: List<String>): List<RemoteQuotaState> =
        remoteNames.map { name ->
            val q = remoteRepository.about(name).getOrNull()
            RemoteQuotaState(remoteName = name, used = q?.used, total = q?.total, free = q?.free)
        }

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

    /** Clears all lifetime stats. Wired to a "reset all statistics" user action. */
    suspend fun reset() = appStatsDao.clear()

    /** Clears sync run history for a specific remote. Lifetime counters are unaffected. */
    suspend fun resetRemote(remoteName: String) = syncRunDao.deleteByRemoteName(remoteName)

    /** Clears all sync run rows. Lifetime counters in AppStatsEntity are unaffected. */
    suspend fun resetAllRuns() = syncRunDao.deleteAll()
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
