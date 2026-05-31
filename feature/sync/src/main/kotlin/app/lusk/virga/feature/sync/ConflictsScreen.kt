package app.lusk.virga.feature.sync

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.data.ConflictChoice
import app.lusk.virga.core.common.model.Conflict
import app.lusk.virga.core.designsystem.component.EmptyState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(
    onBack: () -> Unit,
    viewModel: ConflictsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val inSelectionMode = state.selectedIds.isNotEmpty()
    var pendingChoice by remember { mutableStateOf<Pair<Conflict, ConflictChoice>?>(null) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (inSelectionMode) {
                            stringResource(R.string.conflicts_selection_count, state.selectedIds.size)
                        } else {
                            stringResource(R.string.conflicts_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = if (inSelectionMode) viewModel::clearSelection else onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.conflicts_cd_back),
                        )
                    }
                },
                actions = {
                    if (state.conflicts.isNotEmpty()) {
                        BulkActionRow(
                            onKeepAllLocal = { viewModel.requestBulkChoice(ConflictChoice.KEEP_VARIANT_1) },
                            onKeepAllRemote = { viewModel.requestBulkChoice(ConflictChoice.KEEP_VARIANT_2) },
                            onKeepBoth = { viewModel.requestBulkChoice(ConflictChoice.KEEP_BOTH) },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.conflicts.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.conflicts_empty),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.conflicts, key = { it.id }) { conflict ->
                    ConflictCard(
                        conflict = conflict,
                        resolving = state.resolvingId == conflict.id,
                        isSelected = conflict.id in state.selectedIds,
                        inSelectionMode = inSelectionMode,
                        onChoice = { choice -> pendingChoice = conflict to choice },
                        onLongClick = { viewModel.toggleSelection(conflict.id) },
                        onCheckChange = { viewModel.toggleSelection(conflict.id) },
                    )
                }
            }
        }
    }

    // Per-conflict confirm dialog
    pendingChoice?.let { (conflict, choice) ->
        val (titleRes, bodyRes) = conflictDialogStrings(choice)
        AlertDialog(
            onDismissRequest = { pendingChoice = null },
            title = { Text(stringResource(titleRes)) },
            text = { Text(stringResource(bodyRes)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resolve(conflict, choice)
                    pendingChoice = null
                }) { Text(stringResource(R.string.conflicts_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingChoice = null }) {
                    Text(stringResource(R.string.conflicts_dialog_cancel))
                }
            },
        )
    }

    // Bulk confirm dialog
    state.pendingBulkChoice?.let { choice ->
        val (titleRes, bodyRes) = conflictDialogStrings(choice)
        val count = state.selectedIds.size.let { if (it == 0) state.conflicts.size else it }
        AlertDialog(
            onDismissRequest = viewModel::cancelBulkChoice,
            title = { Text(stringResource(titleRes)) },
            text = {
                Text(stringResource(R.string.conflicts_bulk_body, count, stringResource(bodyRes)))
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmBulkChoice) {
                    Text(stringResource(R.string.conflicts_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelBulkChoice) {
                    Text(stringResource(R.string.conflicts_dialog_cancel))
                }
            },
        )
    }
}

private fun conflictDialogStrings(choice: ConflictChoice): Pair<Int, Int> = when (choice) {
    ConflictChoice.KEEP_VARIANT_1 ->
        R.string.conflicts_dialog_keep_local_title to R.string.conflicts_dialog_keep_local_body
    ConflictChoice.KEEP_VARIANT_2 ->
        R.string.conflicts_dialog_keep_remote_title to R.string.conflicts_dialog_keep_remote_body
    ConflictChoice.KEEP_BOTH ->
        R.string.conflicts_dialog_keep_both_title to R.string.conflicts_dialog_keep_both_body
}

@Composable
private fun BulkActionRow(
    onKeepAllLocal: () -> Unit,
    onKeepAllRemote: () -> Unit,
    onKeepBoth: () -> Unit,
) {
    Row {
        TextButton(onClick = onKeepAllLocal) { Text(stringResource(R.string.conflicts_bulk_keep_local)) }
        TextButton(onClick = onKeepAllRemote) { Text(stringResource(R.string.conflicts_bulk_keep_remote)) }
        TextButton(onClick = onKeepBoth) { Text(stringResource(R.string.conflicts_bulk_keep_both)) }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ConflictCard(
    conflict: Conflict,
    resolving: Boolean,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    onChoice: (ConflictChoice) -> Unit,
    onLongClick: () -> Unit,
    onCheckChange: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongClick, onClick = { if (inSelectionMode) onCheckChange() }),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inSelectionMode) {
                    Checkbox(checked = isSelected, onCheckedChange = { onCheckChange() }, modifier = Modifier.padding(end = 8.dp))
                }
                Text(
                    conflict.basePath,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(R.string.conflicts_on_remote, conflict.remoteName),
                style = MaterialTheme.typography.bodySmall,
            )
            val detectedAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(conflict.detectedAtEpochMs))
            Text(
                stringResource(R.string.conflicts_detected_at, detectedAt),
                style = MaterialTheme.typography.labelSmall,
            )
            VariantRow(stringResource(R.string.conflicts_label_local), conflict.variant1Path, conflict.variant1Size)
            VariantRow(stringResource(R.string.conflicts_label_remote), conflict.variant2Path, conflict.variant2Size)

            if (resolving) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onChoice(ConflictChoice.KEEP_VARIANT_1) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { onClick(label = "Keep local — deletes remote version", action = null) },
                    ) { Text(stringResource(R.string.conflicts_btn_keep_local)) }
                    OutlinedButton(
                        onClick = { onChoice(ConflictChoice.KEEP_VARIANT_2) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { onClick(label = "Keep remote — overwrites local version", action = null) },
                    ) { Text(stringResource(R.string.conflicts_btn_keep_remote)) }
                    OutlinedButton(
                        onClick = { onChoice(ConflictChoice.KEEP_BOTH) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { onClick(label = "Keep both versions", action = null) },
                    ) { Text(stringResource(R.string.conflicts_btn_keep_both)) }
                }
            }
        }
    }
}

@Composable
private fun VariantRow(label: String, path: String, size: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelMedium)
        Column(Modifier.weight(1f)) {
            Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatFileSize(size), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "ConflictCard light", showBackground = true)
@Preview(name = "ConflictCard dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConflictCardPreview() {
    Surface {
        ConflictCard(
            conflict = Conflict(
                id = 1L, taskId = 1L, remoteName = "gdrive",
                basePath = "DCIM/Camera/IMG_20240101.jpg",
                variant1Path = "DCIM/Camera/IMG_20240101.jpg",
                variant1Size = 3_456_789L,
                variant2Path = "DCIM/Camera/IMG_20240101.conflict-1704067200.jpg",
                variant2Size = 3_456_800L,
                detectedAtEpochMs = 0L,
            ),
            resolving = false,
            isSelected = false,
            inSelectionMode = false,
            onChoice = {},
            onLongClick = {},
            onCheckChange = {},
        )
    }
}

