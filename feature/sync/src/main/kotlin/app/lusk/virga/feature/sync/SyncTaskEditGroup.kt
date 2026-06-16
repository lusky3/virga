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
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * B10: Sync group and sort-order sub-section shown inside the Advanced section
 * of the task editor. Exposes groupTag (free-text) and sortOrder (integer).
 */
@Composable
internal fun GroupSection(form: SyncTaskForm, viewModel: SyncTaskEditViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        HorizontalDivider()
        Text(
            stringResource(R.string.sync_edit_group_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = VirgaSpacing.xs),
        )
        OutlinedTextField(
            value = form.groupTag,
            onValueChange = { viewModel.update { f -> f.copy(groupTag = it) } },
            label = { Text(stringResource(R.string.sync_edit_field_group_tag)) },
            placeholder = { Text(stringResource(R.string.sync_edit_field_group_tag_placeholder)) },
            supportingText = { Text(stringResource(R.string.sync_edit_field_group_tag_hint)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.sortOrderText,
            onValueChange = { raw ->
                // Allow an optional leading '-' followed by digits only, so the shown
                // text never diverges from the parsed value ("1-2"/"--5" can't form).
                val digits = raw.filter { it.isDigit() }
                val n = if (raw.startsWith("-")) "-$digits" else digits
                viewModel.update { f ->
                    // Empty or lone "-" parse to 0 so display and stored value agree.
                    f.copy(sortOrderText = n, sortOrder = n.toIntOrNull() ?: 0)
                }
            },
            label = { Text(stringResource(R.string.sync_edit_field_sort_order)) },
            placeholder = { Text(stringResource(R.string.sync_edit_field_sort_order_placeholder)) },
            supportingText = { Text(stringResource(R.string.sync_edit_field_sort_order_hint)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
