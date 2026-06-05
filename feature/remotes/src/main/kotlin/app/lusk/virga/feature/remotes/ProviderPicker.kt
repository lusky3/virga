package app.lusk.virga.feature.remotes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.lusk.virga.core.designsystem.component.RemoteProviderMark
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import app.lusk.virga.core.rclone.PickerEntry
import app.lusk.virga.core.rclone.SetupKind

/**
 * Searchable provider picker. Entries are displayed in catalog order (pinned first).
 * Wrappers are separated into their own section at the bottom.
 */
@Composable
internal fun ProviderPicker(
    entries: List<PickerEntry>,
    setupKindFor: (String) -> SetupKind,
    onSelect: (PickerEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else {
            val q = query.trim().lowercase()
            entries.filter { it.type.lowercase().contains(q) || it.description.lowercase().contains(q) }
        }
    }
    val (wrappers, providers) = filtered.partition { setupKindFor(it.type) == SetupKind.Wrapper }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.remotes_picker_search)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        LazyColumn(
            modifier = Modifier.heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(VirgaSpacing.xs),
        ) {
            items(providers, key = { it.type }) { entry ->
                ProviderRow(entry = entry, onClick = { onSelect(entry) })
            }
            if (wrappers.isNotEmpty()) {
                item(key = "__wrapper_divider") {
                    Column {
                        HorizontalDivider(Modifier.padding(vertical = VirgaSpacing.sm))
                        Text(
                            stringResource(R.string.remotes_picker_wrappers_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = VirgaSpacing.xs),
                        )
                    }
                }
                items(wrappers, key = { it.type }) { entry ->
                    ProviderRow(entry = entry, onClick = { onSelect(entry) })
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(entry: PickerEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = VirgaSpacing.sm, horizontal = VirgaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
    ) {
        RemoteProviderMark(type = entry.type, contentDescription = entry.description)
        Column {
            Text(entry.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                entry.type,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
