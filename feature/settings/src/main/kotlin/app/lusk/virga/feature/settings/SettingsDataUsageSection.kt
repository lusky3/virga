package app.lusk.virga.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.designsystem.component.ToggleRow
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * Settings section for metered data cap configuration and monthly usage display.
 *
 * Shows a toggle to enable the monthly metered cap, an editable MB field (only
 * when the cap is enabled), and a read-only display of bytes used this month
 * over metered connections.
 */
@Composable
internal fun DataUsageSection(
    state: AppPreferences,
    monthlyUsedBytes: Long,
    onCapEnabledChange: (Boolean) -> Unit,
    onCapMbChange: (Long) -> Unit,
) {
    HorizontalDivider()
    SectionTitle(stringResource(R.string.settings_section_data_usage))

    ToggleRow(
        label = stringResource(R.string.settings_toggle_metered_cap),
        checked = state.meteredCapEnabled,
        onChange = onCapEnabledChange,
    )

    if (state.meteredCapEnabled) {
        CapMbField(
            currentMb = state.meteredCapMb,
            onCapMbChange = onCapMbChange,
        )
        Text(
            text = stringResource(R.string.settings_metered_cap_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val usedMb = monthlyUsedBytes / (1024L * 1024L)
    Text(
        text = stringResource(R.string.settings_metered_used_this_month, usedMb),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CapMbField(
    currentMb: Long,
    onCapMbChange: (Long) -> Unit,
) {
    var draft by remember(currentMb) { mutableStateOf(if (currentMb > 0) currentMb.toString() else "") }
    var hasBeenFocused by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it.filter { c -> c.isDigit() } },
        label = { Text(stringResource(R.string.settings_metered_cap_mb_label)) },
        placeholder = { Text(stringResource(R.string.settings_metered_cap_mb_placeholder)) },
        supportingText = { Text(stringResource(R.string.settings_metered_cap_mb_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                    hasBeenFocused = true
                } else if (hasBeenFocused) {
                    onCapMbChange(draft.toLongOrNull() ?: 0L)
                }
            },
    )
}
