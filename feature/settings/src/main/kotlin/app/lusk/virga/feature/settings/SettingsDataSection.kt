package app.lusk.virga.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * Settings section providing cache clearing, log clearing, and full app reset.
 *
 * Each action presents a confirmation dialog before proceeding. The reset action
 * uses a destructive-coloured confirm button and an explicit warning. Outcomes
 * are reported via [onCacheClear], [onLogsClear], and [onReset] callbacks so the
 * caller can show snackbars.
 */
@Composable
internal fun DataSection(
    onCacheClear: () -> Unit,
    onLogsClear: () -> Unit,
    onReset: () -> Unit,
) {
    var showCacheDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    HorizontalDivider()
    SectionTitle(stringResource(R.string.settings_section_data_reset))
    DataActionButtons(
        onCache = { showCacheDialog = true },
        onLogs = { showLogsDialog = true },
        onReset = { showResetDialog = true },
    )

    if (showCacheDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_dialog_clear_cache_title),
            body = stringResource(R.string.settings_dialog_clear_cache_body),
            confirmLabel = stringResource(R.string.settings_dialog_confirm),
            onConfirm = { showCacheDialog = false; onCacheClear() },
            onDismiss = { showCacheDialog = false },
        )
    }
    if (showLogsDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_dialog_clear_logs_title),
            body = stringResource(R.string.settings_dialog_clear_logs_body),
            confirmLabel = stringResource(R.string.settings_dialog_confirm),
            onConfirm = { showLogsDialog = false; onLogsClear() },
            onDismiss = { showLogsDialog = false },
        )
    }
    if (showResetDialog) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.settings_dialog_reset_app_title),
            body = stringResource(R.string.settings_dialog_reset_app_body),
            confirmLabel = stringResource(R.string.settings_dialog_confirm),
            onConfirm = { showResetDialog = false; onReset() },
            onDismiss = { showResetDialog = false },
        )
    }
}

@Composable
private fun DataActionButtons(
    onCache: () -> Unit,
    onLogs: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
    ) {
        TextButton(onClick = onCache) { Text(stringResource(R.string.settings_btn_clear_cache)) }
        TextButton(onClick = onLogs) { Text(stringResource(R.string.settings_btn_clear_logs)) }
    }
    TextButton(onClick = onReset) {
        Text(
            text = stringResource(R.string.settings_btn_reset_app),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dialog_cancel)) }
        },
    )
}

/** An [AlertDialog] with the confirm button styled in [MaterialTheme.colorScheme.error]. */
@Composable
private fun DestructiveConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dialog_cancel)) }
        },
    )
}
