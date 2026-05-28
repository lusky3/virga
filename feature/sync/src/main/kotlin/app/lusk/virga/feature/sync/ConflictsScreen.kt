package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.data.ConflictChoice
import app.lusk.virga.core.database.entity.ConflictEntity
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(
    onBack: () -> Unit,
    viewModel: ConflictsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conflicts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.conflicts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No conflicts to resolve.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.conflicts, key = { it.id }) { conflict ->
                    ConflictCard(
                        conflict = conflict,
                        resolving = state.resolvingId == conflict.id,
                        onChoice = { viewModel.resolve(conflict, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(
    conflict: ConflictEntity,
    resolving: Boolean,
    onChoice: (ConflictChoice) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(conflict.basePath, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "on ${conflict.remoteName}:",
                style = MaterialTheme.typography.bodySmall,
            )
            VariantRow(label = "Variant 1", path = conflict.variant1Path, size = conflict.variant1Size)
            VariantRow(label = "Variant 2", path = conflict.variant2Path, size = conflict.variant2Size)

            if (resolving) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onChoice(ConflictChoice.KEEP_VARIANT_1) }) { Text("Keep 1") }
                    OutlinedButton(onClick = { onChoice(ConflictChoice.KEEP_VARIANT_2) }) { Text("Keep 2") }
                    OutlinedButton(onClick = { onChoice(ConflictChoice.KEEP_BOTH) }) { Text("Keep both") }
                }
            }
        }
    }
}

@Composable
private fun VariantRow(label: String, path: String, size: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelMedium)
        Column(Modifier.fillMaxWidth()) {
            Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatSize(size), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024; i++
    }
    return String.format(Locale.US, "%.1f %s", value, units[i])
}
