package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * Renders the time-of-day editor for calendar schedule tasks.
 *
 * When [scheduleTimes] is empty, shows the single-time stepper (existing behaviour).
 * When non-empty, shows chips for each time plus an "Add time" button and the
 * single-time stepper is hidden (multi-time mode).
 * The user can add additional times or remove existing chips.
 */
/** Bundles the time-editor values so [ScheduleTimesEditor] stays under the param limit. */
internal data class ScheduleTimesState(
    val singleHour: Int,
    val singleMinute: Int,
    val scheduleTimes: List<Int>,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleTimesEditor(
    state: ScheduleTimesState,
    onSingleTimeChange: (hour: Int, minute: Int) -> Unit,
    onAddTime: (minuteOfDay: Int) -> Unit,
    onRemoveTime: (index: Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        if (state.scheduleTimes.isEmpty()) {
            SingleTimeStepper(
                hour = state.singleHour,
                minute = state.singleMinute,
                onTimeChange = onSingleTimeChange,
            )
            TextButton(
                onClick = {
                    // Promote the current single-time to the multi-time list.
                    onAddTime(state.singleHour * 60 + state.singleMinute)
                },
                modifier = Modifier.padding(start = VirgaSpacing.xs),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(VirgaSpacing.xs))
                Text(stringResource(R.string.sync_schedule_add_time))
            }
        } else {
            Text(stringResource(R.string.sync_schedule_time_label), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
                state.scheduleTimes.forEachIndexed { index, minuteOfDay ->
                    val h = minuteOfDay / 60
                    val m = minuteOfDay % 60
                    FilterChip(
                        selected = true,
                        onClick = { onRemoveTime(index) },
                        label = { Text("%02d:%02d".format(h, m)) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.sync_schedule_remove_time),
                                modifier = Modifier.padding(start = 2.dp),
                            )
                        },
                    )
                }
                // "Add time" chip that opens a simple inline stepper dialog.
                AddTimeChip(onAddTime = onAddTime)
            }
        }
    }
}

@Composable
private fun SingleTimeStepper(
    hour: Int,
    minute: Int,
    onTimeChange: (hour: Int, minute: Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sync_schedule_time_label), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(VirgaSpacing.md))
        TimeStepperRow(
            hour = hour,
            minute = minute,
            onTimeChange = onTimeChange,
        )
    }
}

@Composable
internal fun TimeStepperRow(
    hour: Int,
    minute: Int,
    onTimeChange: (hour: Int, minute: Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ScheduleStepper(
            value = "%02d".format(hour),
            onDecrement = { onTimeChange((hour + 23) % 24, minute) },
            onIncrement = { onTimeChange((hour + 1) % 24, minute) },
        )
        Text(":", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = VirgaSpacing.xs))
        ScheduleStepper(
            value = "%02d".format(minute),
            onDecrement = { onTimeChange(hour, (minute + 55) % 60) },
            onIncrement = { onTimeChange(hour, (minute + 5) % 60) },
        )
    }
}

@Composable
private fun ScheduleStepper(value: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDecrement) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.sync_schedule_decrement))
        }
        Text(value, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onIncrement) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sync_schedule_increment))
        }
    }
}

/**
 * A chip that, when clicked, lets the user pick a new time with inline steppers.
 * Uses local state for the draft hour/minute; calls [onAddTime] on confirmation.
 */
@Composable
private fun AddTimeChip(onAddTime: (Int) -> Unit) {
    val draftHour = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(9) }
    val draftMinute = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    val editing = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    if (!editing.value) {
        FilterChip(
            selected = false,
            onClick = { editing.value = true },
            label = { Text(stringResource(R.string.sync_schedule_add_time)) },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.xs),
        ) {
            TimeStepperRow(
                hour = draftHour.value,
                minute = draftMinute.value,
                onTimeChange = { h, m -> draftHour.value = h; draftMinute.value = m },
            )
            IconButton(onClick = {
                onAddTime(draftHour.value * 60 + draftMinute.value)
                editing.value = false
                draftHour.value = 9
                draftMinute.value = 0
            }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sync_schedule_add_time))
            }
            IconButton(onClick = { editing.value = false }) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        }
    }
}
