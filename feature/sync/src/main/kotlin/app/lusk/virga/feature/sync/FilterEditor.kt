package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * Tier-1 include/exclude filter builder (WS2.1). Produces the same newline-joined
 * rclone `FilterRule` string the engine already consumes (`+ pattern` to include,
 * `- pattern` to skip). Shows current rules as removable chips, an add row, and a
 * raw "edit as text" escape hatch for power users. The typed UI and the text box
 * both write the one canonical [filters] string.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterEditor(filters: String, onFiltersChange: (String) -> Unit) {
    val rules = remember(filters) { filters.lines().filter { it.isNotBlank() } }
    var include by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }

    fun commitRule() {
        val p = pattern.trim()
        if (p.isEmpty()) return
        val line = (if (include) "+ " else "- ") + p
        onFiltersChange((rules + line).joinToString("\n"))
        pattern = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        Text(stringResource(R.string.sync_edit_filters_label), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.sync_edit_filters_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (rules.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
                rules.forEachIndexed { index, rule ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(rule) },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    onFiltersChange(
                                        rules.filterIndexed { i, _ -> i != index }.joinToString("\n"),
                                    )
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.sync_edit_filters_remove),
                                )
                            }
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = !include,
                    onClick = { include = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text(stringResource(R.string.sync_edit_filters_skip)) },
                )
                SegmentedButton(
                    selected = include,
                    onClick = { include = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text(stringResource(R.string.sync_edit_filters_include)) },
                )
            }
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text(stringResource(R.string.sync_edit_filters_pattern_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { commitRule() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sync_edit_filters_add))
            }
        }

        TextButton(onClick = { showRaw = !showRaw }) {
            Text(
                stringResource(
                    if (showRaw) R.string.sync_edit_filters_hide_text
                    else R.string.sync_edit_filters_edit_text,
                ),
            )
        }
        if (showRaw) {
            OutlinedTextField(
                value = filters,
                onValueChange = onFiltersChange,
                label = { Text(stringResource(R.string.sync_edit_filters_raw_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
