package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.lusk.virga.core.designsystem.component.ToggleRow
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * B8 retry-config sub-section shown inside the Advanced section of the task editor.
 * Exposes maxRetries, retryOnRclone, backoffSeconds, and backoffExponential.
 */
@Composable
internal fun RetryConfigSection(form: SyncTaskForm, viewModel: SyncTaskEditViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        HorizontalDivider()
        Text(
            stringResource(R.string.sync_edit_retry_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = VirgaSpacing.xs),
        )
        OutlinedTextField(
            value = form.maxRetriesText,
            onValueChange = { raw ->
                val n = raw.filter { it.isDigit() }
                viewModel.update { f ->
                    // Keep numeric state in lock-step with the typed text; the floor is
                    // applied once at save() so multi-digit entry isn't disrupted mid-type.
                    f.copy(maxRetriesText = n, maxRetries = n.toIntOrNull() ?: f.maxRetries)
                }
            },
            label = { Text(stringResource(R.string.sync_edit_field_max_retries)) },
            placeholder = { Text(stringResource(R.string.sync_edit_field_max_retries_placeholder)) },
            supportingText = { Text(stringResource(R.string.sync_edit_field_max_retries_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(
            label = stringResource(R.string.sync_edit_field_retry_on_rclone),
            checked = form.retryOnRclone,
            onChange = { v -> viewModel.update { f -> f.copy(retryOnRclone = v) } },
        )
        OutlinedTextField(
            value = form.backoffSecondsText,
            onValueChange = { raw ->
                val n = raw.filter { it.isDigit() }
                viewModel.update { f ->
                    // Numeric state tracks the typed text; the 10s WorkManager floor is
                    // applied once at save() (the hint documents the minimum).
                    f.copy(backoffSecondsText = n, backoffSeconds = n.toLongOrNull() ?: f.backoffSeconds)
                }
            },
            label = { Text(stringResource(R.string.sync_edit_field_backoff_seconds)) },
            placeholder = { Text(stringResource(R.string.sync_edit_field_backoff_seconds_placeholder)) },
            supportingText = { Text(stringResource(R.string.sync_edit_field_backoff_seconds_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(
            label = stringResource(R.string.sync_edit_field_backoff_exponential),
            checked = form.backoffExponential,
            onChange = { v -> viewModel.update { f -> f.copy(backoffExponential = v) } },
        )
    }
}
