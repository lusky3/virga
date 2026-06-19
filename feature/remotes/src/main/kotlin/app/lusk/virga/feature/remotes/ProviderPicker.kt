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
import androidx.compose.ui.text.input.ImeAction
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
    /**
     * UI-M2: gates selection on a usable remote name. When false, every entry is
     * disabled and a hint points at the name field — a remote can't be created
     * without a name regardless of the path, and a bundled-OAuth pick would
     * otherwise launch the browser flow with a blank name and dead-end. Applied
     * uniformly so the picker doesn't grey out only some providers (which read as
     * a glitch); see [name field hint] below.
     */
    selectionEnabled: Boolean = true,
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
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // A name is required for every remote, so gate the whole list on it uniformly
        // rather than disabling only the bundled-OAuth rows (which looked like a glitch).
        if (!selectionEnabled) {
            Text(
                stringResource(R.string.remotes_picker_name_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = VirgaSpacing.xs, bottom = VirgaSpacing.xs),
            )
        }

        LazyColumn(
            modifier = Modifier.heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(VirgaSpacing.xs),
        ) {
            items(providers, key = { it.type }) { entry ->
                ProviderRow(
                    entry = entry,
                    enabled = selectionEnabled,
                    onClick = { onSelect(entry) },
                )
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
                    ProviderRow(entry = entry, enabled = selectionEnabled, onClick = { onSelect(entry) })
                }
            }
        }

        if (filtered.isEmpty() && query.isNotBlank()) {
            Text(
                stringResource(R.string.remotes_picker_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(VirgaSpacing.md),
            )
        }
    }
}

@Composable
private fun ProviderRow(entry: PickerEntry, onClick: () -> Unit, enabled: Boolean = true) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = VirgaSpacing.sm, horizontal = VirgaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
    ) {
        RemoteProviderMark(type = entry.type, contentDescription = entry.description)
        Column {
            Text(entry.description, style = MaterialTheme.typography.bodyMedium, color = contentColor)
            Text(
                entry.type,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else contentColor,
            )
        }
    }
}
