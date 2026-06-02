package app.lusk.virga.feature.sync

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.designsystem.component.EmptyState

/**
 * Per-run log viewer (WS2.5): monospaced, scrollable, live-searchable, and
 * shareable. Opened from Run Detail when the run has a [logPath]. The log is
 * built by the worker and redacted, so it carries no secrets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    logPath: String,
    onBack: () -> Unit,
    viewModel: LogViewerViewModel = hiltViewModel(),
) {
    LaunchedEffect(logPath) { viewModel.load(logPath) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_viewer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sync_history_cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, viewModel.rawText())
                        }
                        context.startActivity(
                            Intent.createChooser(send, context.getString(R.string.log_viewer_share)),
                        )
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.log_viewer_share))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.log_viewer_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = VirgaSpacing.md, vertical = VirgaSpacing.sm),
            )
            if (!state.loading && state.totalLines == 0) {
                EmptyState(title = stringResource(R.string.log_viewer_empty))
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = VirgaSpacing.md)) {
                    items(state.visibleLines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}
