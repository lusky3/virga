package app.lusk.virga.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.model.LifetimeStats
import app.lusk.virga.core.data.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val repo: StatsRepository,
) : ViewModel() {

    val state: StateFlow<LifetimeStats> = repo.stats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LifetimeStats())

    fun resetStats() {
        viewModelScope.launch { repo.reset() }
    }
}
