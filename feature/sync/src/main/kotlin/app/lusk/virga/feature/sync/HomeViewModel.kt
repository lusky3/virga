package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.data.StatsRepository
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State for the Home dashboard tab. */
data class HomeUiState(
    val homeStatus: HomeStatus = HomeStatus.Idle,
    val lifetimeBytes: Long = 0L,
    val lifetimeRuns: Long = 0L,
    val taskCount: Int = 0,
    val remoteCount: Int = 0,
    val hasEnabledTasks: Boolean = false,
)

/**
 * Drives the Home tab: the overall [HomeStatus] hero, the lifetime stat-glance,
 * counts of tasks/remotes, and a "back up now" action. Read-only aggregation over
 * the same repositories the rest of the app uses; status derivation is shared with
 * the Sync screen via [deriveHomeStatus].
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val taskRepository: SyncTaskRepository,
    historyRepository: SyncHistoryRepository,
    statsRepository: StatsRepository,
    remoteRepository: RemoteRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(
            taskRepository.tasks,
            historyRepository.recentRuns,
            statsRepository.stats,
            remoteRepository.remotes,
        ) { tasks, runs, stats, remotes ->
            val latestRuns = runs
                .groupBy { it.taskId }
                .mapValues { (_, v) -> v.maxBy { it.startedAtEpochMs } }
            HomeUiState(
                homeStatus = deriveHomeStatus(tasks, latestRuns),
                lifetimeBytes = stats.totalBytesTransferred,
                lifetimeRuns = stats.totalRuns,
                taskCount = tasks.size,
                remoteCount = remotes.size,
                hasEnabledTasks = tasks.any { it.enabled },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    /** Triggers an on-demand run of every enabled task ("Back up now"). */
    fun backUpNow() = viewModelScope.launch {
        scheduler.syncAllEnabled()
    }
}
