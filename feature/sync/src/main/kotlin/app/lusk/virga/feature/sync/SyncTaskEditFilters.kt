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

/** Which size/age field changed, routed through the single [SizeAgeFilterEditor] callback. */
internal enum class SizeAgeFilterField { MIN_SIZE, MAX_SIZE, MIN_AGE, MAX_AGE }

/**
 * Immutable view state for [SizeAgeFilterEditor]. Holding the four values and their
 * validation errors in one object keeps the composable's parameter list small
 * (Codacy/detekt cap function params), mirroring the state-holder pattern used by the
 * remotes export dialog. Blank value = unset (rclone ignores the corresponding key).
 */
internal data class SizeAgeFilterState(
    val minSize: String = "",
    val maxSize: String = "",
    val minAge: String = "",
    val maxAge: String = "",
    val minSizeError: String? = null,
    val maxSizeError: String? = null,
    val minAgeError: String? = null,
    val maxAgeError: String? = null,
)

/**
 * Size and age filter fields for the task editor (B5).
 * Each field maps to an rclone `_filter` key: MinSize, MaxSize, MinAge, MaxAge.
 * Blank = unset (rclone ignores the key). Validation errors are surfaced inline.
 */
@Composable
internal fun SizeAgeFilterEditor(
    state: SizeAgeFilterState,
    onChange: (SizeAgeFilterField, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        Text(
            text = stringResource(R.string.sync_edit_filters_label),
            style = MaterialTheme.typography.labelLarge,
        )
        SizeField(
            label = stringResource(R.string.sync_edit_min_size),
            value = state.minSize,
            error = state.minSizeError,
            onValueChange = { onChange(SizeAgeFilterField.MIN_SIZE, it) },
        )
        SizeField(
            label = stringResource(R.string.sync_edit_max_size),
            value = state.maxSize,
            error = state.maxSizeError,
            onValueChange = { onChange(SizeAgeFilterField.MAX_SIZE, it) },
        )
        AgeField(
            label = stringResource(R.string.sync_edit_min_age),
            value = state.minAge,
            error = state.minAgeError,
            onValueChange = { onChange(SizeAgeFilterField.MIN_AGE, it) },
        )
        AgeField(
            label = stringResource(R.string.sync_edit_max_age),
            value = state.maxAge,
            error = state.maxAgeError,
            onValueChange = { onChange(SizeAgeFilterField.MAX_AGE, it) },
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
