package app.lusk.virga.feature.sync

import androidx.annotation.StringRes
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
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
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/** Collapsible Advanced section containing BW limits and buffer size. */
@Composable
internal fun AdvancedSection(form: SyncTaskForm, viewModel: SyncTaskEditViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = VirgaSpacing.xs),
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
            Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md)) {
                ValidatedAsciiField(
                    value = form.bwLimitWifi,
                    onValueChange = { viewModel.update { f -> f.copy(bwLimitWifi = it) } },
                    text = FieldText(
                        R.string.sync_edit_field_bw_wifi,
                        R.string.sync_edit_field_bw_wifi_placeholder,
                        R.string.sync_edit_field_bw_wifi_hint,
                    ),
                    error = form.bwLimitWifiError,
                )
                ValidatedAsciiField(
                    value = form.bwLimitMetered,
                    onValueChange = { viewModel.update { f -> f.copy(bwLimitMetered = it) } },
                    text = FieldText(
                        R.string.sync_edit_field_bw_metered,
                        R.string.sync_edit_field_bw_metered_placeholder,
                        R.string.sync_edit_field_bw_metered_hint,
                    ),
                    error = form.bwLimitMeteredError,
                )
                ValidatedAsciiField(
                    value = form.bufferSize,
                    onValueChange = { viewModel.update { f -> f.copy(bufferSize = it) } },
                    text = FieldText(
                        R.string.sync_edit_field_buffer,
                        R.string.sync_edit_field_buffer_placeholder,
                        R.string.sync_edit_field_buffer_hint,
                    ),
                    error = form.bufferSizeError,
                )
                // B6: data cap per run (MaxTransfer + CutoffMode=CAUTIOUS)
                ValidatedAsciiField(
                    value = form.maxTransfer,
                    onValueChange = { viewModel.update { f -> f.copy(maxTransfer = it) } },
                    text = FieldText(
                        R.string.sync_edit_field_max_transfer,
                        R.string.sync_edit_field_max_transfer_placeholder,
                        R.string.sync_edit_field_max_transfer_hint,
                    ),
                    error = form.maxTransferError,
                )
                // ---- WS3.1 Tier-2 options ------------------------------------
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sync_edit_field_checksum)) },
                    supportingContent = { Text(stringResource(R.string.sync_edit_field_checksum_hint)) },
                    trailingContent = {
                        Switch(
                            checked = form.checksum,
                            onCheckedChange = { viewModel.update { f -> f.copy(checksum = it) } },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.backupDir,
                    onValueChange = { viewModel.update { f -> f.copy(backupDir = it) } },
                    label = { Text(stringResource(R.string.sync_edit_field_backup_dir)) },
                    placeholder = { Text(stringResource(R.string.sync_edit_field_backup_dir_placeholder)) },
                    supportingText = { Text(stringResource(R.string.sync_edit_field_backup_dir_hint)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.maxDeleteText,
                    onValueChange = { raw ->
                        val n = raw.filter { it.isDigit() }
                        viewModel.update { f ->
                            f.copy(maxDeleteText = n, maxDelete = n.toIntOrNull())
                        }
                    },
                    label = { Text(stringResource(R.string.sync_edit_field_max_delete)) },
                    placeholder = { Text(stringResource(R.string.sync_edit_field_max_delete_placeholder)) },
                    supportingText = { Text(stringResource(R.string.sync_edit_field_max_delete_hint)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.extraConfig,
                    onValueChange = { viewModel.update { f -> f.copy(extraConfig = it) } },
                    label = { Text(stringResource(R.string.sync_edit_field_extra_config)) },
                    placeholder = { Text(stringResource(R.string.sync_edit_field_extra_config_placeholder)) },
                    isError = form.extraConfigError != null,
                    supportingText = if (form.extraConfigError != null) {
                        { Text(form.extraConfigError!!) }
                    } else {
                        { Text(stringResource(R.string.sync_edit_field_extra_config_hint)) }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                // B8: per-task retry configuration
                RetryConfigSection(form = form, viewModel = viewModel)
            }
        }
    }
}

/** Static label/placeholder/hint string resources for a [ValidatedAsciiField]. */
private class FieldText(
    @get:StringRes val label: Int,
    @get:StringRes val placeholder: Int,
    @get:StringRes val hint: Int,
)

/**
 * A single-line ASCII [OutlinedTextField] that shows [error] in place of the
 * hint when validation fails. The three bandwidth/buffer inputs share this exact
 * shape, differing only in their bound value and string resources.
 */
@Composable
private fun ValidatedAsciiField(
    value: String,
    onValueChange: (String) -> Unit,
    text: FieldText,
    error: String?,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(text.label)) },
        placeholder = { Text(stringResource(text.placeholder)) },
        isError = error != null,
        supportingText = { Text(error ?: stringResource(text.hint)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
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
    scheduleTimes: List<Int> = emptyList(),
    onAddTime: (minuteOfDay: Int) -> Unit = {},
    onRemoveTime: (index: Int) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
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
        ScheduleTimesEditor(
            state = ScheduleTimesState(
                singleHour = hour,
                singleMinute = minute,
                scheduleTimes = scheduleTimes,
            ),
            onSingleTimeChange = onTimeChange,
            onAddTime = onAddTime,
            onRemoveTime = onRemoveTime,
        )
        val mask = days.fold(0) { acc, d -> acc or (1 shl (d - 1)) }
        val cronText = if (scheduleTimes.isNotEmpty()) {
            app.lusk.virga.sync.SyncSchedule.cronString(mask, scheduleTimes)
        } else {
            app.lusk.virga.sync.SyncSchedule.cronString(mask, hour, minute)
        }
        cronText?.let { cron ->
            Text(
                stringResource(R.string.sync_schedule_cron_preview, cron),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.xs)) {
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

