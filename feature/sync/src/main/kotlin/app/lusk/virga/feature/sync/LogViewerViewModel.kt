package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class LogViewerUiState(
    val query: String = "",
    val visibleLines: List<String> = emptyList(),
    val totalLines: Int = 0,
    val loading: Boolean = true,
)

/**
 * Reads a per-run log file (WS2.5) and exposes its lines, filtered live by a
 * search [query]. The full text is available via [rawText] for the Share action.
 */
@HiltViewModel
class LogViewerViewModel @Inject constructor() : ViewModel() {

    private val allLines = MutableStateFlow<List<String>>(emptyList())
    private val query = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)
    private var initialized = false

    /** Idempotent: reads the log file once. Safe from a `LaunchedEffect`. */
    fun load(path: String) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { File(path).readText() }.getOrDefault("")
            }
            allLines.value = if (text.isEmpty()) emptyList() else text.trimEnd('\n').lines()
            loaded.value = true
        }
    }

    fun setQuery(q: String) { query.value = q }

    fun rawText(): String = allLines.value.joinToString("\n")

    val uiState: StateFlow<LogViewerUiState> =
        combine(allLines, query, loaded) { lines, q, isLoaded ->
            LogViewerUiState(
                query = q,
                visibleLines = if (q.isBlank()) lines else lines.filter { it.contains(q, ignoreCase = true) },
                totalLines = lines.size,
                loading = !isLoaded,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogViewerUiState())
}
