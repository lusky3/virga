package app.lusk.virga.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.datastore.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Local draft state for bandwidth limit fields — committed on focus-loss/submit.
    var bwLimitMetered by remember(prefs.defaultBwLimitMetered) {
        mutableStateOf(prefs.defaultBwLimitMetered.orEmpty())
    }
    var bwLimitWifi by remember(prefs.defaultBwLimitWifi) {
        mutableStateOf(prefs.defaultBwLimitWifi.orEmpty())
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_section_appearance))
            Text(stringResource(R.string.settings_label_theme), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = prefs.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(mode.name.lowercase()) },
                    )
                }
            }
            ToggleRow(
                label = stringResource(R.string.settings_toggle_dynamic_color),
                checked = prefs.dynamicColor,
                onChange = viewModel::setDynamicColor,
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_sync_defaults))
            ToggleRow(
                label = stringResource(R.string.settings_toggle_wifi_only),
                checked = prefs.wifiOnlyByDefault,
                onChange = viewModel::setWifiOnly,
            )
            ToggleRow(
                label = stringResource(R.string.settings_toggle_require_charging),
                checked = prefs.requireChargingByDefault,
                onChange = viewModel::setRequireCharging,
            )

            OutlinedTextField(
                value = bwLimitMetered,
                onValueChange = { bwLimitMetered = it },
                label = { Text(stringResource(R.string.settings_field_bw_metered)) },
                placeholder = { Text(stringResource(R.string.settings_field_bw_metered_placeholder)) },
                supportingText = { Text(stringResource(R.string.settings_field_bw_metered_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = bwLimitWifi,
                onValueChange = { bwLimitWifi = it },
                label = { Text(stringResource(R.string.settings_field_bw_wifi)) },
                placeholder = { Text(stringResource(R.string.settings_field_bw_wifi_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextButton(
                onClick = {
                    viewModel.setDefaultBwLimits(
                        wifi = bwLimitWifi.trim().ifBlank { null },
                        metered = bwLimitMetered.trim().ifBlank { null },
                    )
                },
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.settings_btn_save_bw)) }

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_battery))
            Text(
                stringResource(R.string.settings_battery_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        )
                    }
                }) { Text(stringResource(R.string.settings_btn_battery_settings)) }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://dontkillmyapp.com".toUri()))
                }) { Text(stringResource(R.string.settings_btn_dontkillmyapp)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/**
 * A full-row toggle: the label Text and Switch are merged into a single
 * toggleable node so TalkBack announces the label together with the state.
 * Task #24: apply Modifier.toggleable on the Row; pass onCheckedChange = null to Switch.
 */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}
