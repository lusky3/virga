package app.lusk.virga.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.TrendDay
import app.lusk.virga.core.data.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repo: StatsRepository,
) : ViewModel() {

    val state: StateFlow<StatsUiState> = combine(
        repo.stats,
        repo.remoteStats,
        repo.taskStats,
        repo.trendFlow(TREND_DAYS),
    ) { lifetime, remotes, tasks, rawTrend ->
        StatsUiState(
            lifetime = lifetime,
            remoteStats = remotes,
            taskStats = tasks,
            trendBytes = buildDenseTrend(rawTrend, TREND_DAYS),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun resetAll() {
        viewModelScope.launch { repo.reset() }
    }

    fun resetRuns() {
        viewModelScope.launch { repo.resetAllRuns() }
    }

    fun resetRemote(remoteName: String) {
        viewModelScope.launch { repo.resetRemote(remoteName) }
    }

    companion object {
        const val TREND_DAYS = 30
    }
}

/** Fills a dense [days]-element list (index 0 = oldest, index days-1 = today).
 *  Gaps (days with no runs) are 0. [rawTrend] contains raw epoch-day integers. */
internal fun buildDenseTrend(rawTrend: List<TrendDay>, days: Int): List<Long> {
    if (rawTrend.isEmpty()) return List(days) { 0L }
    val today = System.currentTimeMillis() / 86_400_000L
    val byDay = rawTrend.associateBy { it.dayOffset.toLong() }
    return (0 until days).map { offset ->
        val epochDay = today - (days - 1 - offset)
        byDay[epochDay]?.bytes ?: 0L
    }
}
