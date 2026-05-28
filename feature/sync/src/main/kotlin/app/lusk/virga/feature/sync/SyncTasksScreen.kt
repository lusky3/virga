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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import app.lusk.virga.core.database.entity.SyncTaskEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncTasksScreen(
    onAddTask: () -> Unit,
    onEditTask: (Long) -> Unit,
    viewModel: SyncTasksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sync tasks") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTask) {
                Icon(Icons.Filled.Add, contentDescription = "Add sync task")
            }
        },
    ) { padding ->
        if (!state.loading && state.tasks.isEmpty()) {
            EmptyTasks(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.tasks, key = { it.id }) { task ->
                    SyncTaskCard(
                        task = task,
                        onRun = { viewModel.syncNow(task.id) },
                        onToggle = { viewModel.setEnabled(task, it) },
                        onClick = { onEditTask(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncTaskCard(
    task: SyncTaskEntity,
    onRun: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(task.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${task.sourcePath} → ${task.remoteName}:${task.remotePath}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${task.direction.name.lowercase()} • " +
                        (task.intervalMinutes?.let { "every ${it}m" } ?: "manual"),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Switch(checked = task.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onRun) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Sync now")
            }
        }
    }
}

@Composable
private fun EmptyTasks(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "No sync tasks yet.\nTap + to create one.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
