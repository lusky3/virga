package app.lusk.virga.feature.stats

import app.lusk.virga.core.common.model.LifetimeStats
import app.lusk.virga.core.common.model.RemoteQuotaState
import app.lusk.virga.core.common.model.RemoteStat

/** Per-task stats with display name already resolved. */
data class TaskStatUi(
    val name: String,
    val totalRuns: Long,
    val successRuns: Long,
    val bytes: Long,
)

/** Consolidated UI state for StatsScreen. */
data class StatsUiState(
    val lifetime: LifetimeStats = LifetimeStats(),
    val remoteStats: List<RemoteStat> = emptyList(),
    val taskStats: List<TaskStatUi> = emptyList(),
    /** Dense 30-day trend: index 0 = 30 days ago, index 29 = today. */
    val trendBytes: List<Long> = emptyList(),
    val quotas: List<RemoteQuotaState> = emptyList(),
)
