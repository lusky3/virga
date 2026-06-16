package app.lusk.virga.feature.stats

import app.lusk.virga.core.common.model.LifetimeStats
import app.lusk.virga.core.common.model.RemoteQuotaState
import app.lusk.virga.core.common.model.RemoteStat
import app.lusk.virga.core.common.model.TaskStat

/** Consolidated UI state for StatsScreen. */
data class StatsUiState(
    val lifetime: LifetimeStats = LifetimeStats(),
    val remoteStats: List<RemoteStat> = emptyList(),
    val taskStats: List<TaskStat> = emptyList(),
    /** Dense 30-day trend: index 0 = 30 days ago, index 29 = today. */
    val trendBytes: List<Long> = emptyList(),
    val quotas: List<RemoteQuotaState> = emptyList(),
)
