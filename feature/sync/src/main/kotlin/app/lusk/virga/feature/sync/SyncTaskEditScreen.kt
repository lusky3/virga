package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.SyncDirection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncTaskEditScreen(
    taskId: Long,
    onBack: () -> Unit,
    viewModel: SyncTaskEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(taskId) { viewModel.load(taskId) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val remotes by viewModel.availableRemotes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (taskId > 0) "Edit task" else "New sync task") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text("Task name") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.sourcePath,
                onValueChange = { v -> viewModel.update { it.copy(sourcePath = v) } },
                label = { Text("Local path (e.g. /storage/emulated/0/DCIM)") },
                modifier = Modifier.fillMaxWidth(),
            )

            RemoteDropdown(
                remotes = remotes,
                selected = form.remoteName,
                onSelect = { v -> viewModel.update { it.copy(remoteName = v) } },
            )
            OutlinedTextField(
                value = form.remotePath,
                onValueChange = { v -> viewModel.update { it.copy(remotePath = v) } },
                label = { Text("Remote path (e.g. /Backup)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Direction", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncDirection.entries.forEach { dir ->
                    FilterChip(
                        selected = form.direction == dir,
                        onClick = { viewModel.update { it.copy(direction = dir) } },
                        label = { Text(dir.name.lowercase()) },
                    )
                }
            }

            IntervalDropdown(
                selected = form.intervalMinutes,
                onSelect = { v -> viewModel.update { it.copy(intervalMinutes = v) } },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = form.wifiOnly,
                    onCheckedChange = { v -> viewModel.update { it.copy(wifiOnly = v) } },
                )
                Text("Wi-Fi only", modifier = Modifier.padding(start = 8.dp))
            }

            Button(
                onClick = { viewModel.save(onBack) },
                enabled = form.isValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteDropdown(remotes: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.ifBlank { "Select a remote" },
            onValueChange = {},
            readOnly = true,
            label = { Text("Remote") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (remotes.isEmpty()) {
                DropdownMenuItem(text = { Text("No remotes — add one first") }, onClick = {})
            }
            remotes.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(name); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalDropdown(selected: Int?, onSelect: (Int?) -> Unit) {
    val options = listOf<Pair<String, Int?>>(
        "Manual only" to null,
        "Every 15 min" to 15,
        "Every 30 min" to 30,
        "Every hour" to 60,
        "Every 6 hours" to 360,
        "Every 12 hours" to 720,
        "Daily" to 1440,
    )
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.second == selected }?.first ?: "Manual only"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Schedule") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}
