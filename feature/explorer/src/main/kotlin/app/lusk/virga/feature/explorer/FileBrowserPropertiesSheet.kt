package app.lusk.virga.feature.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import java.text.DateFormat

/** Thread-safe date+time formatter for the properties sheet. */
private fun formatModTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(epochMs)

/**
 * Modal bottom sheet showing metadata for [item].
 * Invoke [onDismiss] to close it; the caller controls [FileBrowserUiState.propertiesItem].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileBrowserPropertiesSheet(
    item: FileItem,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        PropertiesContent(item = item)
    }
}

@Composable
internal fun PropertiesContent(item: FileItem) {
    val dash = stringResource(R.string.explorer_properties_dash)
    val mime = item.mimeType
    val typeLabel = if (item.isDir) {
        stringResource(R.string.explorer_properties_type_folder)
    } else if (mime != null) {
        stringResource(R.string.explorer_properties_type_file_mime, mime)
    } else {
        stringResource(R.string.explorer_properties_type_file)
    }
    val sizeLabel = if (item.isDir) dash else formatFileSize(item.size)
    // Treat 0 (and negative) epoch as "unknown" to match the list row's `ms > 0L` threshold.
    val modLabel = item.modTimeEpochMs?.takeIf { it > 0L }?.let { formatModTime(it) } ?: dash

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = VirgaSpacing.md),
    ) {
        PropertiesRow(
            label = stringResource(R.string.explorer_properties_name),
            value = item.name,
        )
        HorizontalDivider()
        PropertiesRow(
            label = stringResource(R.string.explorer_properties_path),
            value = item.path,
        )
        HorizontalDivider()
        PropertiesRow(
            label = stringResource(R.string.explorer_properties_type),
            value = typeLabel,
        )
        HorizontalDivider()
        PropertiesRow(
            label = stringResource(R.string.explorer_properties_size),
            value = sizeLabel,
        )
        HorizontalDivider()
        PropertiesRow(
            label = stringResource(R.string.explorer_properties_modified),
            value = modLabel,
        )
    }
}

@Composable
private fun PropertiesRow(label: String, value: String) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(value) },
    )
}
