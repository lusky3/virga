package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * Size and age filter fields for the task editor (B5).
 * Each field maps to an rclone `_filter` key: MinSize, MaxSize, MinAge, MaxAge.
 * Blank = unset (rclone ignores the key). Validation errors are surfaced inline.
 */
@Composable
internal fun SizeAgeFilterEditor(
    minSize: String,
    maxSize: String,
    minAge: String,
    maxAge: String,
    minSizeError: String?,
    maxSizeError: String?,
    minAgeError: String?,
    maxAgeError: String?,
    onMinSizeChange: (String) -> Unit,
    onMaxSizeChange: (String) -> Unit,
    onMinAgeChange: (String) -> Unit,
    onMaxAgeChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        Text(
            text = stringResource(R.string.sync_edit_filters_label),
            style = MaterialTheme.typography.labelLarge,
        )
        SizeField(
            label = stringResource(R.string.sync_edit_min_size),
            value = minSize,
            error = minSizeError,
            onValueChange = onMinSizeChange,
        )
        SizeField(
            label = stringResource(R.string.sync_edit_max_size),
            value = maxSize,
            error = maxSizeError,
            onValueChange = onMaxSizeChange,
        )
        AgeField(
            label = stringResource(R.string.sync_edit_min_age),
            value = minAge,
            error = minAgeError,
            onValueChange = onMinAgeChange,
        )
        AgeField(
            label = stringResource(R.string.sync_edit_max_age),
            value = maxAge,
            error = maxAgeError,
            onValueChange = onMaxAgeChange,
        )
    }
}

@Composable
private fun SizeField(
    label: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.sync_edit_size_placeholder)) },
        supportingText = {
            Text(
                text = error ?: stringResource(R.string.sync_edit_size_hint),
                color = if (error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        isError = error != null,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { error?.let { error(it) } },
    )
}

@Composable
private fun AgeField(
    label: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.sync_edit_age_placeholder)) },
        supportingText = {
            Text(
                text = error ?: stringResource(R.string.sync_edit_age_hint),
                color = if (error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        isError = error != null,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { error?.let { error(it) } },
    )
}
