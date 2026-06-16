package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.designsystem.component.ToggleRow
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * B7 conflict-strategy sub-section shown inside the Advanced section of the task editor.
 * Shows a conflict-resolve dropdown for BISYNC tasks and a conflict-check toggle for
 * one-way (UPLOAD/DOWNLOAD) tasks. Both controls are hidden when they don't apply.
 */
@Composable
internal fun ConflictStrategySection(form: SyncTaskForm, viewModel: SyncTaskEditViewModel) {
    val isBisync = form.direction == SyncDirection.BISYNC
    val isOneWay = form.direction != SyncDirection.BISYNC
    if (!isBisync && !isOneWay) return
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        HorizontalDivider()
        Text(
            "Conflict strategy",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = VirgaSpacing.xs),
        )
        if (isBisync) {
            ConflictResolveDropdown(
                selected = form.conflictResolve,
                onSelect = { v -> viewModel.update { f -> f.copy(conflictResolve = v) } },
            )
        }
        if (isOneWay) {
            Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.xs)) {
                ToggleRow(
                    label = "Pre-sync conflict check",
                    checked = form.conflictCheck,
                    onChange = { v -> viewModel.update { f -> f.copy(conflictCheck = v) } },
                )
                Text(
                    "Before syncing, detect files that differ on both sides and show them as advisory conflicts. Does not block or change the sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val CONFLICT_RESOLVE_OPTIONS = listOf(
    "" to "Default (rclone decides)",
    "none" to "None",
    "newer" to "Newer wins",
    "older" to "Older wins",
    "larger" to "Larger wins",
    "smaller" to "Smaller wins",
    "path1" to "Local (path1) wins",
    "path2" to "Remote (path2) wins",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConflictResolveDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = CONFLICT_RESOLVE_OPTIONS.firstOrNull { it.first == selected }?.second
        ?: CONFLICT_RESOLVE_OPTIONS[0].second
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Conflict resolve") },
            supportingText = { Text("How bisync picks the winner when both sides changed the same file.") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CONFLICT_RESOLVE_OPTIONS.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}
