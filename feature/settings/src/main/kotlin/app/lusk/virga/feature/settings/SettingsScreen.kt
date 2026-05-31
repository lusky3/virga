package app.lusk.virga.feature.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.datastore.ThemeMode
import app.lusk.virga.core.ui.ToggleRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noBrowserMsg = stringResource(R.string.settings_snack_no_browser)

    // Draft state for bandwidth fields — committed on focus-loss rather than
    // requiring an explicit Save button. reset() keys on the persisted value so
    // the field tracks any external change (e.g. fresh install defaults).
    var bwLimitMetered by remember(prefs.defaultBwLimitMetered) {
        mutableStateOf(prefs.defaultBwLimitMetered.orEmpty())
    }
    var bwLimitWifi by remember(prefs.defaultBwLimitWifi) {
        mutableStateOf(prefs.defaultBwLimitWifi.orEmpty())
    }

    var showHowItWorksDialog by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    if (showHowItWorksDialog) {
        HowVirgaWorksDialog(onDismiss = { showHowItWorksDialog = false })
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_section_appearance))
            Text(
                stringResource(R.string.settings_label_theme),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() },
            )
            ThemeSegmentedRow(
                selected = prefs.themeMode,
                onSelect = viewModel::setThemeMode,
            )
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
            Text(
                stringResource(R.string.settings_sync_defaults_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var meteredHasBeenFocused by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = bwLimitMetered,
                onValueChange = { bwLimitMetered = it },
                label = { Text(stringResource(R.string.settings_field_bw_metered)) },
                placeholder = { Text(stringResource(R.string.settings_field_bw_metered_placeholder)) },
                supportingText = { Text(stringResource(R.string.settings_field_bw_metered_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            meteredHasBeenFocused = true
                        } else if (meteredHasBeenFocused) {
                            viewModel.setDefaultBwLimits(
                                wifi = bwLimitWifi.trim().ifBlank { null },
                                metered = bwLimitMetered.trim().ifBlank { null },
                            )
                        }
                    },
                singleLine = true,
            )
            var wifiHasBeenFocused by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = bwLimitWifi,
                onValueChange = { bwLimitWifi = it },
                label = { Text(stringResource(R.string.settings_field_bw_wifi)) },
                placeholder = { Text(stringResource(R.string.settings_field_bw_wifi_placeholder)) },
                supportingText = { Text(stringResource(R.string.settings_field_bw_wifi_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            wifiHasBeenFocused = true
                        } else if (wifiHasBeenFocused) {
                            viewModel.setDefaultBwLimits(
                                wifi = bwLimitWifi.trim().ifBlank { null },
                                metered = bwLimitMetered.trim().ifBlank { null },
                            )
                        }
                    },
                singleLine = true,
            )

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
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://dontkillmyapp.com".toUri()))
                    }.onFailure {
                        scope.launch { snackbarHostState.showSnackbar(noBrowserMsg) }
                    }
                }) { Text(stringResource(R.string.settings_btn_dontkillmyapp)) }
            }

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_help_about))
            TextButton(
                onClick = { showHowItWorksDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.settings_item_how_virga_works),
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, "https://rclone.org/docs/".toUri()),
                        )
                    }.onFailure {
                        scope.launch { snackbarHostState.showSnackbar(noBrowserMsg) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.settings_item_rclone_docs),
                    modifier = Modifier.weight(1f),
                )
            }
            val versionName = remember {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.packageManager.getPackageInfo(
                            context.packageName,
                            PackageManager.PackageInfoFlags.of(0L),
                        ).versionName ?: "—"
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
                    }
                }.getOrDefault("—")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_item_about),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_about_version, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Bottom spacing so last item clears nav bar
            Spacer(Modifier.padding(bottom = 8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSegmentedRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val labels = mapOf(
        ThemeMode.SYSTEM to R.string.settings_theme_system,
        ThemeMode.LIGHT to R.string.settings_theme_light,
        ThemeMode.DARK to R.string.settings_theme_dark,
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
    ) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = ThemeMode.entries.size,
                ),
                label = { Text(stringResource(labels.getValue(mode))) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun HowVirgaWorksDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_how_virga_works_title)) },
        text = { Text(stringResource(R.string.settings_how_virga_works_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_dialog_ok))
            }
        },
    )
}
