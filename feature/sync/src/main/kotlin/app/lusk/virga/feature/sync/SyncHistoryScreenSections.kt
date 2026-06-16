package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

@Composable
internal fun HistorySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.sync_history_search_hint)) },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = VirgaSpacing.md, vertical = VirgaSpacing.sm),
    )
}

@Composable
internal fun StatusFilterRow(
    statusFilter: SyncStatus?,
    onSelectStatus: (SyncStatus?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = VirgaSpacing.md, vertical = VirgaSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
    ) {
        item {
            FilterChip(
                selected = statusFilter == null,
                onClick = { onSelectStatus(null) },
                label = { Text(stringResource(R.string.sync_history_status_all)) },
            )
        }
        items(SyncStatus.entries.filter { it != SyncStatus.IDLE }) { status ->
            FilterChip(
                selected = statusFilter == status,
                onClick = {
                    val next = if (statusFilter == status) null else status
                    onSelectStatus(next)
                },
                label = { Text(statusLabel(status)) },
            )
        }
    }
}

@Composable
internal fun TaskFilterChips(
    tasks: List<HistoryTaskFilter>,
    selectedTaskId: Long?,
    onSelectTask: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = VirgaSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
    ) {
        item {
            FilterChip(
                selected = selectedTaskId == null,
                onClick = { onSelectTask(null) },
                label = { Text(stringResource(R.string.sync_history_filter_all)) },
            )
        }
        items(tasks, key = { it.id }) { task ->
            FilterChip(
                selected = selectedTaskId == task.id,
                onClick = { onSelectTask(task.id) },
                label = { Text(task.name, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun statusLabel(status: SyncStatus): String = stringResource(
    when (status) {
        SyncStatus.SUCCESS -> R.string.sync_history_status_success
        SyncStatus.FAILED -> R.string.sync_history_status_failed
        SyncStatus.RUNNING -> R.string.sync_history_status_running
        SyncStatus.QUEUED -> R.string.sync_history_status_queued
        SyncStatus.CANCELLED -> R.string.sync_history_status_cancelled
        SyncStatus.IDLE -> R.string.sync_history_status_idle
    },
)
