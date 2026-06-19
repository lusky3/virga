package app.lusk.virga.feature.explorer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.designsystem.component.VirgaBottomSheet
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/** Action callbacks for the per-file action sheet. */
data class FileActionCallbacks(
    val onOpen: () -> Unit,
    val onShare: () -> Unit,
    val onSave: () -> Unit,
    val onUpload: () -> Unit,
)

/**
 * Per-file action sheet: Open, Share, Save to device, Upload file here.
 *
 * Owns no network logic — all actions are delegated to the caller ([FileBrowserScreen]).
 * Preview (ACTION_VIEW) is covered by [FileActionCallbacks.onOpen]; no dedicated
 * in-app viewer this release.
 */
@Composable
internal fun FileBrowserActionSheet(
    item: FileItem,
    onDismiss: () -> Unit,
    actions: FileActionCallbacks,
) {
    VirgaBottomSheet(onDismiss = onDismiss, scrimDescription = stringResource(R.string.explorer_sheet_dismiss)) {
        ActionSheetContent(item = item, onDismiss = onDismiss, actions = actions)
    }
}

@Composable
internal fun ActionSheetContent(
    item: FileItem,
    onDismiss: () -> Unit,
    actions: FileActionCallbacks,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = VirgaSpacing.md),
    ) {
        Text(
            text = item.name,
            modifier = Modifier.padding(horizontal = VirgaSpacing.md, vertical = VirgaSpacing.sm),
        )
        HorizontalDivider()
        ActionItem(
            label = stringResource(R.string.explorer_action_open),
            icon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
            onClick = { onDismiss(); actions.onOpen() },
        )
        HorizontalDivider()
        ActionItem(
            label = stringResource(R.string.explorer_action_share),
            icon = { Icon(Icons.Filled.Share, contentDescription = null) },
            onClick = { onDismiss(); actions.onShare() },
        )
        HorizontalDivider()
        ActionItem(
            label = stringResource(R.string.explorer_action_save),
            icon = { Icon(Icons.Filled.Download, contentDescription = null) },
            onClick = { onDismiss(); actions.onSave() },
        )
        HorizontalDivider()
        ActionItem(
            label = stringResource(R.string.explorer_action_upload),
            icon = { Icon(Icons.Filled.Upload, contentDescription = null) },
            onClick = { onDismiss(); actions.onUpload() },
        )
    }
}

@Composable
private fun ActionItem(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = icon,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
    )
}
