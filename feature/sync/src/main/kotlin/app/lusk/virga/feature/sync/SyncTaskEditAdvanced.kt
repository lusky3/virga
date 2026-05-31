package app.lusk.virga.feature.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Collapsible Advanced section containing BW limits and buffer size. */
@Composable
internal fun AdvancedSection(form: SyncTaskForm, viewModel: SyncTaskEditViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.sync_edit_advanced_title), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
                Text(stringResource(if (expanded) R.string.sync_edit_advanced_collapse else R.string.sync_edit_advanced_expand))
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = form.bwLimitWifi,
                    onValueChange = { viewModel.update { f -> f.copy(bwLimitWifi = it) } },
                    label = { Text(stringResource(R.string.sync_edit_field_bw_wifi)) },
                    placeholder = { Text(stringResource(R.string.sync_edit_field_bw_wifi_placeholder)) },
                    isError = form.bwLimitWifiError != null,
                    supportingText = if (form.bwLimitWifiError != null) {
                        { Text(form.bwLimitWifiError!!) }
                    } else {
                        { Text(stringResource(R.string.sync_edit_field_bw_wifi_hint)) }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.bwLimitMetered,
                    onValueChange = { viewModel.update { f -> f.copy(bwLimitMetered = it) } },
                    label = { Text(stringResource(R.string.sync_edit_field_bw_metered)) },
                    placeholder = { Text(stringResource(R.string.sync_edit_field_bw_metered_placeholder)) },
                    isError = form.bwLimitMeteredError != null,
                    supportingText = if (form.bwLimitMeteredError != null) {
                        { Text(form.bwLimitMeteredError!!) }
                    } else {
                        { Text(stringResource(R.string.sync_edit_field_bw_metered_hint)) }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.bufferSize,
                    onValueChange = { viewModel.update { f -> f.copy(bufferSize = it) } },
                    label = { Text(stringResource(R.string.sync_edit_field_buffer)) },
                    placeholder = { Text(stringResource(R.string.sync_edit_field_buffer_placeholder)) },
                    isError = form.bufferSizeError != null,
                    supportingText = if (form.bufferSizeError != null) {
                        { Text(form.bufferSizeError!!) }
                    } else {
                        { Text(stringResource(R.string.sync_edit_field_buffer_hint)) }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private data class IntervalOption(val labelRes: Int?, val minutes: Int?)

private val intervalOptions = listOf(
    IntervalOption(null, null),
    IntervalOption(R.string.sync_interval_15min, 15),
    IntervalOption(R.string.sync_interval_30min, 30),
    IntervalOption(R.string.sync_interval_1hour, 60),
    IntervalOption(R.string.sync_interval_6hours, 360),
    IntervalOption(R.string.sync_interval_12hours, 720),
    IntervalOption(R.string.sync_interval_daily, 1440),
)

private const val CUSTOM_SENTINEL = -1

/** ISO weekday short labels (index 0 = Monday … 6 = Sunday). */
private val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * Calendar-schedule editor: weekday chips + a time-of-day stepper, with the
 * equivalent cron expression shown for reference. Shown when the schedule
 * dropdown is set to "Specific days & time".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarScheduleEditor(
    days: Set<Int>,
    hour: Int,
    minute: Int,
    daysError: String?,
    onToggleDay: (Int) -> Unit,
    onTimeChange: (hour: Int, minute: Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WEEKDAY_LABELS.forEachIndexed { index, label ->
                val iso = index + 1 // 1 = Monday … 7 = Sunday
                FilterChip(
                    selected = iso in days,
                    onClick = { onToggleDay(iso) },
                    label = { Text(label) },
                )
            }
        }
        if (daysError != null) {
            Text(daysError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sync_schedule_time_label), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(16.dp))
            Stepper(
                value = "%02d".format(hour),
                onDecrement = { onTimeChange((hour + 23) % 24, minute) },
                onIncrement = { onTimeChange((hour + 1) % 24, minute) },
            )
            Text(":", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
            Stepper(
                value = "%02d".format(minute),
                onDecrement = { onTimeChange(hour, (minute + 55) % 60) },
                onIncrement = { onTimeChange(hour, (minute + 5) % 60) },
            )
        }
        val mask = days.fold(0) { acc, d -> acc or (1 shl (d - 1)) }
        app.lusk.virga.sync.SyncSchedule.cronString(mask, hour, minute)?.let { cron ->
            Text(
                stringResource(R.string.sync_schedule_cron_preview, cron),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stepper(value: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IntervalDropdown(
    selected: Int?,
    customMinutes: Int?,
    customIntervalError: String? = null,
    isCalendar: Boolean = false,
    onSelect: (Int?) -> Unit,
    onCustomMinutes: (Int?) -> Unit,
    onSelectCalendar: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val isCustom = !isCalendar && selected == CUSTOM_SENTINEL
    val labelRes = if (isCalendar) null else intervalOptions.firstOrNull { it.minutes == selected }?.labelRes
    val displayLabel = when {
        isCalendar -> stringResource(R.string.sync_interval_calendar_option)
        isCustom -> stringResource(R.string.sync_interval_custom, customMinutes ?: 0)
        labelRes != null -> stringResource(labelRes)
        else -> stringResource(R.string.sync_interval_manual)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = displayLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.sync_edit_field_schedule)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                intervalOptions.forEach { option ->
                    val label = option.labelRes?.let { stringResource(it) }
                        ?: stringResource(R.string.sync_interval_manual)
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onSelect(option.minutes); expanded = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_interval_custom_option)) },
                    onClick = { onSelect(CUSTOM_SENTINEL); expanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_interval_calendar_option)) },
                    onClick = { onSelectCalendar(); expanded = false },
                )
            }
        }
        if (isCustom) {
            OutlinedTextField(
                value = customMinutes?.toString() ?: "",
                onValueChange = { v -> onCustomMinutes(v.toIntOrNull()) },
                label = { Text(stringResource(R.string.sync_interval_custom_label)) },
                isError = customIntervalError != null,
                supportingText = customIntervalError?.let { msg -> { Text(msg) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

