package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.ui.EmptyState
import app.lusk.virga.core.data.ConflictChoice
import app.lusk.virga.core.database.entity.ConflictEntity
import java.text.DateFormat
import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.ui.tooling.preview.Preview
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(
    onBack: () -> Unit,
    viewModel: ConflictsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Pending destructive confirmation: (conflict, choice) or null.
    var pendingChoice by remember { mutableStateOf<Pair<ConflictEntity, ConflictChoice>?>(null) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflicts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.conflicts_cd_back),
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
                        onChoice = { choice -> pendingChoice = conflict to choice },
                    )
                }
            }
        }
    }

    // Confirmation dialog for destructive keep-one choices.
    pendingChoice?.let { (conflict, choice) ->
        val (titleRes, bodyRes) = when (choice) {
            ConflictChoice.KEEP_VARIANT_1 ->
                R.string.conflicts_dialog_keep_local_title to R.string.conflicts_dialog_keep_local_body
            ConflictChoice.KEEP_VARIANT_2 ->
                R.string.conflicts_dialog_keep_remote_title to R.string.conflicts_dialog_keep_remote_body
            ConflictChoice.KEEP_BOTH ->
                R.string.conflicts_dialog_keep_both_title to R.string.conflicts_dialog_keep_both_body
        }
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
}

@Composable
private fun ConflictCard(
    conflict: ConflictEntity,
    resolving: Boolean,
    onChoice: (ConflictChoice) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                conflict.basePath,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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

            VariantRow(
                label = stringResource(R.string.conflicts_label_local),
                path = conflict.variant1Path,
                size = conflict.variant1Size,
            )
            VariantRow(
                label = stringResource(R.string.conflicts_label_remote),
                path = conflict.variant2Path,
                size = conflict.variant2Size,
            )

            if (resolving) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onChoice(ConflictChoice.KEEP_VARIANT_1) },
                    ) { Text(stringResource(R.string.conflicts_btn_keep_local)) }
                    OutlinedButton(
                        onClick = { onChoice(ConflictChoice.KEEP_VARIANT_2) },
                    ) { Text(stringResource(R.string.conflicts_btn_keep_remote)) }
                    OutlinedButton(
                        onClick = { onChoice(ConflictChoice.KEEP_BOTH) },
                    ) { Text(stringResource(R.string.conflicts_btn_keep_both)) }
                }
            }
        }
    }
}

@Composable
private fun VariantRow(label: String, path: String, size: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label:",
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        // Task #26: use weight(1f) instead of fillMaxWidth so the row distributes space correctly
        Column(Modifier.weight(1f)) {
            Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatFileSize(size), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (Task #26)
// ---------------------------------------------------------------------------

@Preview(name = "ConflictCard light", showBackground = true)
@Preview(name = "ConflictCard dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "ConflictCard fontScale=2", showBackground = true, fontScale = 2f)
@Composable
private fun ConflictCardPreview() {
    Surface {
        ConflictCard(
            conflict = ConflictEntity(
                id = 1L, taskId = 1L, remoteName = "gdrive",
                basePath = "DCIM/Camera/IMG_20240101.jpg",
                variant1Path = "DCIM/Camera/IMG_20240101.jpg",
                variant1Size = 3_456_789L,
                variant2Path = "DCIM/Camera/IMG_20240101.conflict-1704067200.jpg",
                variant2Size = 3_456_800L,
                detectedAtEpochMs = 0L,
            ),
            resolving = false,
            onChoice = {},
        )
    }
}
