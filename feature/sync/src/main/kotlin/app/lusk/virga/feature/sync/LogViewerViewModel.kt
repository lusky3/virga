package app.lusk.virga.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import dagger.hilt.android.lifecycle.HiltViewModel
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
    // True when the log file couldn't be read (missing/pruned/permission) — distinct
    // from a successfully-read but empty log, so the UI can say so instead of showing
    // a blank "no output" that reads as "the sync produced nothing".
    val readFailed: Boolean = false,
)

/**
 * Reads a per-run log file (WS2.5) and exposes its lines, filtered live by a
 * search [query]. The full text is available via [rawText] for the Share action.
 */
@HiltViewModel
class LogViewerViewModel @Inject constructor(
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val allLines = MutableStateFlow<List<String>>(emptyList())
    private val query = MutableStateFlow("")
    private val loaded = MutableStateFlow(false)
    private val readFailed = MutableStateFlow(false)
    private var initialized = false

    /** Idempotent: reads the log file once. Safe from a `LaunchedEffect`. */
    fun load(path: String) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                runCatching { File(path).readText() }
            }
            result.onSuccess { text ->
                allLines.value = if (text.isEmpty()) emptyList() else text.trimEnd('\n').lines()
            }.onFailure {
                allLines.value = emptyList()
                readFailed.value = true
            }
            loaded.value = true
        }
    }

    fun setQuery(q: String) { query.value = q }

    fun rawText(): String = allLines.value.joinToString("\n")

    val uiState: StateFlow<LogViewerUiState> =
        combine(allLines, query, loaded, readFailed) { lines, q, isLoaded, failed ->
            LogViewerUiState(
                query = q,
                visibleLines = if (q.isBlank()) lines else lines.filter { it.contains(q, ignoreCase = true) },
                totalLines = lines.size,
                loading = !isLoaded,
                readFailed = failed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogViewerUiState())
}
