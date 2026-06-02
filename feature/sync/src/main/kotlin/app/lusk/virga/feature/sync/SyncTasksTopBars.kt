package app.lusk.virga.feature.sync

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import app.lusk.virga.core.designsystem.component.SelectionTopBar as DsSelectionTopBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

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
    // Uses the shared contextual-action-mode primitive (WS2.7); the bulk action
    // icons here are this surface's specific operations.
    DsSelectionTopBar(
        title = stringResource(R.string.sync_task_selection_count, count),
        onClear = onClear,
        clearContentDescription = stringResource(R.string.sync_task_cd_clear_selection),
    ) {
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToDeleteCard(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value -> value == SwipeToDismissBoxValue.EndToStart },
    )
    // Fire onDelete once when the row reaches the dismissed position. onDelete
    // stages a pending-removal in the VM (row hides) and the screen shows the
    // Undo snackbar.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    // Will the row delete if released here? Drives the affordance + a haptic tick
    // the moment the swipe passes the dismiss threshold.
    val willDelete = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
    LaunchedEffect(willDelete) {
        if (willDelete) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Red intensifies and the icon grows once past the threshold, so it's
            // clear the swipe will delete (and that releasing commits it).
            val bg by animateColorAsState(
                if (willDelete) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.errorContainer,
                label = "swipeBg",
            )
            val onBg = if (willDelete) MaterialTheme.colorScheme.onError
            else MaterialTheme.colorScheme.onErrorContainer
            val iconScale by animateFloatAsState(if (willDelete) 1.3f else 0.9f, label = "swipeIcon")
            Box(
                Modifier
                    .fillMaxSize()
                    .background(bg, MaterialTheme.shapes.medium)
                    .padding(horizontal = VirgaSpacing.lg),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.sync_task_swipe_delete),
                        color = onBg,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(VirgaSpacing.sm))
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = onBg,
                        modifier = Modifier.scale(iconScale),
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
    ) { content() }
}
