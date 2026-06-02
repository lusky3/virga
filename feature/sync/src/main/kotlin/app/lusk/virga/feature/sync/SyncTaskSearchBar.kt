package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

private data class FilterOption(val filter: TaskFilter, val labelRes: Int)

private val filterOptions = listOf(
    FilterOption(TaskFilter.ALL, R.string.sync_filter_all),
    FilterOption(TaskFilter.ENABLED, R.string.sync_filter_enabled),
    FilterOption(TaskFilter.FAILING, R.string.sync_filter_failing),
    FilterOption(TaskFilter.SCHEDULED, R.string.sync_filter_scheduled),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncTaskSearchBar(
    query: String,
    activeFilter: TaskFilter,
    sortOrder: TaskSortOrder,
    onQueryChange: (String) -> Unit,
    onFilterChange: (TaskFilter) -> Unit,
    onSortChange: (TaskSortOrder) -> Unit,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.sync_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sync_sort_menu_cd))
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sync_sort_name)) },
                            onClick = { onSortChange(TaskSortOrder.NAME); sortMenuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sync_sort_last_run)) },
                            onClick = { onSortChange(TaskSortOrder.LAST_RUN); sortMenuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sync_sort_status)) },
                            onClick = { onSortChange(TaskSortOrder.STATUS); sortMenuExpanded = false },
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = VirgaSpacing.md, vertical = VirgaSpacing.xs),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = VirgaSpacing.md, vertical = VirgaSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
        ) {
            items(filterOptions, key = { it.filter.name }) { option ->
                FilterChip(
                    selected = activeFilter == option.filter,
                    onClick = { onFilterChange(option.filter) },
                    label = { Text(stringResource(option.labelRes)) },
                )
            }
        }
    }
}
