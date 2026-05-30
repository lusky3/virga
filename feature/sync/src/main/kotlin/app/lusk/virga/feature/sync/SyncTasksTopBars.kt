package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainTopBar(
    conflictCount: Int,
    anyRunActive: Boolean,
    onConflicts: () -> Unit,
    onHistory: () -> Unit,
    onSyncAll: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.sync_tasks_title)) },
        actions = {
            IconButton(onClick = onSyncAll) {
                Icon(
                    if (anyRunActive) Icons.Filled.Cancel else Icons.Filled.Sync,
                    contentDescription = stringResource(
                        if (anyRunActive) R.string.sync_task_cd_cancel_all else R.string.sync_task_cd_sync_all,
                    ),
                )
            }
            BadgedBox(
                badge = { if (conflictCount > 0) Badge { Text(conflictCount.toString()) } },
            ) {
                IconButton(onClick = onConflicts) {
                    Icon(
                        Icons.Filled.SyncProblem,
                        contentDescription = stringResource(R.string.sync_task_cd_conflicts),
                    )
                }
            }
            IconButton(onClick = onHistory) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = stringResource(R.string.sync_task_cd_history),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    onBulkRun: () -> Unit,
    onBulkEnable: () -> Unit,
    onBulkDisable: () -> Unit,
    onBulkDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.sync_task_selection_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.sync_task_cd_clear_selection),
                )
            }
        },
        actions = {
            IconButton(onClick = onBulkRun) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.sync_task_cd_sync_now))
            }
            IconButton(onClick = onBulkEnable) {
                Icon(Icons.Filled.CheckBox, contentDescription = stringResource(R.string.sync_task_cd_bulk_enable))
            }
            IconButton(onClick = onBulkDisable) {
                Icon(Icons.Filled.CheckBoxOutlineBlank, contentDescription = stringResource(R.string.sync_task_cd_bulk_disable))
            }
            IconButton(onClick = onBulkDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.sync_task_cd_delete))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToDeleteCard(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value -> value == SwipeToDismissBoxValue.EndToStart },
    )
    // Fire onDelete once when the row reaches the dismissed position.
    // onDelete stages a pending-removal in the VM (row is already hidden) and
    // shows the Undo snackbar. The row never reappears because the VM filters it out.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) { content() }
}
