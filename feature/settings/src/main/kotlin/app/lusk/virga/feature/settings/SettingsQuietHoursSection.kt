package app.lusk.virga.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.designsystem.component.ToggleRow

/** Bundles quiet-hours values so [QuietHoursSection] stays under the parameter limit. */
internal data class QuietHoursUiState(
    val enabled: Boolean,
    val startMinutes: Int,
    val endMinutes: Int,
)

/**
 * Quiet-hours settings section: enable toggle + start/end time pickers shown as
 * dialogs (matching the pattern used for schedule time editing in the task editor).
 * Extracted from SettingsScreen to keep it under the 500-line limit.
 *
 * DEFERRED: per-group and per-task quiet hours (global only in this release).
 */
@Composable
internal fun QuietHoursSection(
    state: QuietHoursUiState,
    onEnabledChange: (Boolean) -> Unit,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
) {
    HorizontalDivider()
    SectionTitle(stringResource(R.string.settings_section_quiet_hours))
    ToggleRow(
        label = stringResource(R.string.settings_toggle_quiet_hours),
        checked = state.enabled,
        onChange = onEnabledChange,
    )
    Text(
        stringResource(R.string.settings_quiet_hours_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.enabled) {
        Spacer(Modifier.height(8.dp))
        QuietHoursTimePickers(
            startMinutes = state.startMinutes,
            endMinutes = state.endMinutes,
            onStartChange = onStartChange,
            onEndChange = onEndChange,
        )
    }
}

@Composable
private fun QuietHoursTimePickers(
    startMinutes: Int,
    endMinutes: Int,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuietHoursTimeColumn(
            label = stringResource(R.string.settings_quiet_hours_start),
            minutes = startMinutes,
            onClick = { showStartPicker = true },
            modifier = Modifier.weight(1f),
        )
        QuietHoursTimeColumn(
            label = stringResource(R.string.settings_quiet_hours_end),
            minutes = endMinutes,
            onClick = { showEndPicker = true },
            modifier = Modifier.weight(1f),
        )
    }

    if (showStartPicker) {
        MinuteTimePickerDialog(
            initialMinutes = startMinutes,
            onConfirm = { onStartChange(it); showStartPicker = false },
            onDismiss = { showStartPicker = false },
        )
    }
    if (showEndPicker) {
        MinuteTimePickerDialog(
            initialMinutes = endMinutes,
            onConfirm = { onEndChange(it); showEndPicker = false },
            onDismiss = { showEndPicker = false },
        )
    }
}

@Composable
private fun QuietHoursTimeColumn(
    label: String,
    minutes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = onClick) { Text(formatMinutesOfDay(minutes)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinuteTimePickerDialog(
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = remember {
        TimePickerState(
            initialHour = initialMinutes / 60,
            initialMinute = initialMinutes % 60,
            is24Hour = true,
        )
    }
    TimePickerDialog(
        state = state,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(state.hour * 60 + state.minute) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    state: TimePickerState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = { TimePicker(state = state) },
        title = { Text(stringResource(R.string.settings_section_quiet_hours)) },
    )
}

/** Formats a minutes-of-day value (0..1439) as "HH:MM". */
internal fun formatMinutesOfDay(minutesOfDay: Int): String {
    val h = minutesOfDay.coerceIn(0, 1439) / 60
    val m = minutesOfDay.coerceIn(0, 1439) % 60
    return "%02d:%02d".format(h, m)
}
