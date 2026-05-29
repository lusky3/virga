package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.SyncDirection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncTaskEditScreen(
    taskId: Long,
    onBack: () -> Unit,
    onNavigateToRemotes: () -> Unit = {},
    viewModel: SyncTaskEditViewModel = hiltViewModel(),
) {
    LaunchedEffect(taskId) { viewModel.load(taskId) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val remotes by viewModel.availableRemotes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (taskId > 0) {
                            stringResource(R.string.sync_edit_title_edit)
                        } else {
                            stringResource(R.string.sync_edit_title_new)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sync_edit_cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text(stringResource(R.string.sync_edit_field_name)) },
                isError = form.name.isBlank(),
                supportingText = if (form.name.isBlank()) {
                    { Text(stringResource(R.string.sync_edit_field_required)) }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.sourcePath,
                onValueChange = { v -> viewModel.update { it.copy(sourcePath = v) } },
                label = { Text(stringResource(R.string.sync_edit_field_source_path)) },
                placeholder = { Text(stringResource(R.string.sync_edit_field_source_placeholder)) },
                isError = form.sourcePath.isBlank(),
                supportingText = if (form.sourcePath.isBlank()) {
                    { Text(stringResource(R.string.sync_edit_field_required)) }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            RemoteDropdown(
                remotes = remotes,
                selected = form.remoteName,
                onSelect = { v -> viewModel.update { it.copy(remoteName = v) } },
                onNavigateToRemotes = onNavigateToRemotes,
            )
            OutlinedTextField(
                value = form.remotePath,
                onValueChange = { v -> viewModel.update { it.copy(remotePath = v) } },
                label = { Text(stringResource(R.string.sync_edit_field_remote_path)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.sync_edit_field_direction), style = MaterialTheme.typography.labelLarge)
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

            // Task #24: merge Row + Switch semantics so TalkBack announces label + state together
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = form.wifiOnly,
                        role = Role.Switch,
                        onValueChange = { v -> viewModel.update { it.copy(wifiOnly = v) } },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.sync_edit_field_wifi_only),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = form.wifiOnly, onCheckedChange = null)
            }

            OutlinedTextField(
                value = form.bwLimitWifi,
                onValueChange = { v -> viewModel.update { it.copy(bwLimitWifi = v) } },
                label = { Text(stringResource(R.string.sync_edit_field_bw_wifi)) },
                placeholder = { Text(stringResource(R.string.sync_edit_field_bw_wifi_placeholder)) },
                isError = form.bwLimitError != null,
                supportingText = if (form.bwLimitError != null) {
                    { Text(form.bwLimitError!!) }
                } else {
                    { Text(stringResource(R.string.sync_edit_field_bw_wifi_hint)) }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.bwLimitMetered,
                onValueChange = { v -> viewModel.update { it.copy(bwLimitMetered = v) } },
                label = { Text(stringResource(R.string.sync_edit_field_bw_metered)) },
                placeholder = { Text(stringResource(R.string.sync_edit_field_bw_metered_placeholder)) },
                isError = form.bwLimitError != null,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = form.bufferSize,
                onValueChange = { v -> viewModel.update { it.copy(bufferSize = v) } },
                label = { Text(stringResource(R.string.sync_edit_field_buffer)) },
                placeholder = { Text(stringResource(R.string.sync_edit_field_buffer_placeholder)) },
                isError = form.bufferSizeError != null,
                supportingText = if (form.bufferSizeError != null) {
                    { Text(form.bufferSizeError!!) }
                } else {
                    { Text(stringResource(R.string.sync_edit_field_buffer_hint)) }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { viewModel.save(onBack) },
                enabled = form.isValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.sync_edit_save)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteDropdown(
    remotes: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onNavigateToRemotes: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.ifBlank { stringResource(R.string.sync_edit_field_remote_placeholder) },
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.sync_edit_field_remote)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = selected.isBlank(),
            supportingText = if (selected.isBlank()) {
                { Text(stringResource(R.string.sync_edit_field_remote_required)) }
            } else null,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (remotes.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_edit_no_remotes_item)) },
                    onClick = {
                        expanded = false
                        onNavigateToRemotes()
                    },
                )
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
    val options = listOf<Pair<Int?, Int?>>(
        null to null,
        R.string.sync_interval_15min to 15,
        R.string.sync_interval_30min to 30,
        R.string.sync_interval_1hour to 60,
        R.string.sync_interval_6hours to 360,
        R.string.sync_interval_12hours to 720,
        R.string.sync_interval_daily to 1440,
    )
    var expanded by remember { mutableStateOf(false) }
    val labelRes = options.firstOrNull { it.second == selected }?.first
    val label = labelRes?.let { stringResource(it) } ?: stringResource(R.string.sync_interval_manual)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.sync_edit_field_schedule)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_interval_manual)) },
                onClick = { onSelect(null); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_interval_15min)) },
                onClick = { onSelect(15); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_interval_30min)) },
                onClick = { onSelect(30); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_interval_1hour)) },
                onClick = { onSelect(60); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_interval_6hours)) },
                onClick = { onSelect(360); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_interval_12hours)) },
                onClick = { onSelect(720); expanded = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sync_interval_daily)) },
                onClick = { onSelect(1440); expanded = false },
            )
        }
    }
}
