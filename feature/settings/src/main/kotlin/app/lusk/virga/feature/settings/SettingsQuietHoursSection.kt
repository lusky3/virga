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
import app.lusk.virga.core.designsystem.component.SettingsLinkRow
import app.lusk.virga.core.designsystem.component.ToggleRow

/**
 * Quiet-hours settings section: enable toggle + start/end time pickers shown as
 * dialogs (matching the pattern used for schedule time editing in the task editor).
 * Extracted from SettingsScreen to keep it under the 500-line limit.
 *
 * DEFERRED: per-group and per-task quiet hours (global only in this release).
 */
@Composable
internal fun QuietHoursSection(
    enabled: Boolean,
    startMinutes: Int,
    endMinutes: Int,
    onEnabledChange: (Boolean) -> Unit,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
) {
    HorizontalDivider()
    SectionTitle(stringResource(R.string.settings_section_quiet_hours))
    ToggleRow(
        label = stringResource(R.string.settings_toggle_quiet_hours),
        checked = enabled,
        onChange = onEnabledChange,
    )
    Text(
        stringResource(R.string.settings_quiet_hours_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (enabled) {
        Spacer(Modifier.height(8.dp))
        QuietHoursTimePickers(
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            onStartChange = onStartChange,
            onEndChange = onEndChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_quiet_hours_start),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = { showStartPicker = true }) {
                Text(formatMinutesOfDay(startMinutes))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_quiet_hours_end),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = { showEndPicker = true }) {
                Text(formatMinutesOfDay(endMinutes))
            }
        }
    }

    if (showStartPicker) {
        val state = remember {
            TimePickerState(
                initialHour = startMinutes / 60,
                initialMinute = startMinutes % 60,
                is24Hour = true,
            )
        }
        TimePickerDialog(
            state = state,
            onDismiss = { showStartPicker = false },
            onConfirm = {
                onStartChange(state.hour * 60 + state.minute)
                showStartPicker = false
            },
        )
    }

    if (showEndPicker) {
        val state = remember {
            TimePickerState(
                initialHour = endMinutes / 60,
                initialMinute = endMinutes % 60,
                is24Hour = true,
            )
        }
        TimePickerDialog(
            state = state,
            onDismiss = { showEndPicker = false },
            onConfirm = {
                onEndChange(state.hour * 60 + state.minute)
                showEndPicker = false
            },
        )
    }
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
