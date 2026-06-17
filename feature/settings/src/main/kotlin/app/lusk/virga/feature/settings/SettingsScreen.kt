package app.lusk.virga.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.TopAppBar
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.datastore.ThemeMode
import app.lusk.virga.core.designsystem.component.SettingsLinkRow
import app.lusk.virga.core.designsystem.component.ToggleRow
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenStats: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    // Build-time flags from the app module (keeps feature:settings BuildConfig-free).
    crashReportingAvailable: Boolean = false,
    storageAccessRelevant: Boolean = false,
    /**
     * Called immediately when the user picks a language, BEFORE the pref is persisted.
     * The app module wires this to LocaleManager.apply() so the locale change takes
     * effect at once without waiting for the DataStore write to round-trip.
     * Defaults to a no-op so preview / instrumented tests don't need to supply it.
     */
    onLanguageChange: (String?) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.state.collectAsStateWithLifecycle()
    val monthlyMeteredBytes by viewModel.monthlyMeteredBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noBrowserMsg = stringResource(R.string.settings_snack_no_browser)
    val noBatterySettingsMsg = stringResource(R.string.settings_snack_no_battery_settings)
    val cacheClearedMsg = stringResource(R.string.settings_snack_cache_cleared)
    val logsClearedMsg = stringResource(R.string.settings_snack_logs_cleared)
    val clearFailedMsg = stringResource(R.string.settings_snack_clear_failed)
    val resetFailedMsg = stringResource(R.string.settings_snack_reset_failed)

    // Draft state for bandwidth fields — committed on focus-loss rather than
    // requiring an explicit Save button. reset() keys on the persisted value so
    // the field tracks any external change (e.g. fresh install defaults).
    var bwLimitMetered by remember(prefs.defaultBwLimitMetered) {
        mutableStateOf(prefs.defaultBwLimitMetered.orEmpty())
    }
    var bwLimitWifi by remember(prefs.defaultBwLimitWifi) {
        mutableStateOf(prefs.defaultBwLimitWifi.orEmpty())
    }

    // Opens an external URL, falling back to a snackbar when no browser is present.
    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar(noBrowserMsg) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = VirgaSpacing.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md),
        ) {
            LanguageSection(
                selectedTag = prefs.appLanguageTag,
                onLanguageSelected = { tag ->
                    onLanguageChange(tag)
                    viewModel.setAppLanguageTag(tag)
                },
            )

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
            Text(
                stringResource(R.string.settings_dynamic_color_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            ToggleRow(
                label = stringResource(R.string.settings_toggle_show_advanced),
                checked = prefs.showAdvancedOptions,
                onChange = viewModel::setShowAdvancedOptions,
            )
            Text(
                stringResource(R.string.settings_sync_defaults_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Both fields commit the current Wi-Fi + metered pair on blur.
            val commitBwLimits: () -> Unit = {
                viewModel.setDefaultBwLimits(
                    wifi = bwLimitWifi.trim().ifBlank { null },
                    metered = bwLimitMetered.trim().ifBlank { null },
                )
            }
            CommitOnBlurField(
                value = bwLimitMetered,
                onValueChange = { bwLimitMetered = it },
                text = FieldText(
                    R.string.settings_field_bw_metered,
                    R.string.settings_field_bw_metered_placeholder,
                    R.string.settings_field_bw_metered_hint,
                ),
                onCommit = commitBwLimits,
            )
            CommitOnBlurField(
                value = bwLimitWifi,
                onValueChange = { bwLimitWifi = it },
                text = FieldText(
                    R.string.settings_field_bw_wifi,
                    R.string.settings_field_bw_wifi_placeholder,
                    R.string.settings_field_bw_wifi_hint,
                ),
                onCommit = commitBwLimits,
            )

            if (storageAccessRelevant) StorageAccessSection()

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_battery))
            Text(
                stringResource(R.string.settings_battery_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            ToggleRow(
                label = stringResource(R.string.settings_toggle_watchdog),
                checked = prefs.watchdogEnabled,
                onChange = viewModel::setWatchdog,
            )
            Text(
                stringResource(R.string.settings_watchdog_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EventTriggersSection(
                state = EventTriggerState(
                    folderChange = prefs.triggerOnFolderChange,
                    wifiConnect = prefs.triggerOnWifiConnect,
                    charge = prefs.triggerOnCharge,
                ),
                onToggle = viewModel::setTrigger,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm)) {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        )
                    }.onFailure {
                        scope.launch { snackbarHostState.showSnackbar(noBatterySettingsMsg) }
                    }
                }) { Text(stringResource(R.string.settings_btn_battery_settings)) }
                TextButton(onClick = { openUrl("https://dontkillmyapp.com") }) {
                    Text(stringResource(R.string.settings_btn_dontkillmyapp))
                }
            }

            // Privacy opt-in (crash reporting); only when a backend is configured. See SettingsPrivacySection.
            if (crashReportingAvailable) {
                PrivacySection(
                    enabled = prefs.crashReportingEnabled,
                    onChange = viewModel::setCrashReporting,
                )
            }

            // Security — opt-in biometric/PIN app lock. See SettingsSecuritySection.
            SecuritySection(
                appLockEnabled = prefs.appLockEnabled,
                onAppLockChange = viewModel::setAppLock,
            )

            // Quiet hours — global blackout window. See SettingsQuietHoursSection.
            QuietHoursSection(
                state = QuietHoursUiState(
                    enabled = prefs.quietHoursEnabled,
                    startMinutes = prefs.quietHoursStartMinutes,
                    endMinutes = prefs.quietHoursEndMinutes,
                ),
                onEnabledChange = viewModel::setQuietHoursEnabled,
                onStartChange = viewModel::setQuietHoursStart,
                onEndChange = viewModel::setQuietHoursEnd,
            )

            RetentionSection(
                days = prefs.runRetentionDays,
                onDaysChange = viewModel::setRunRetentionDays,
            )

            // Notifications — failure-only toggle + OS channel deep-link. See SettingsNotificationsSection.
            NotificationsSection(
                notifyOnFailureOnly = prefs.notifyOnFailureOnly,
                onNotifyOnFailureOnlyChange = viewModel::setNotifyOnFailureOnly,
            )

            // Data usage — metered cap config + monthly usage display.
            DataUsageSection(
                state = prefs,
                monthlyUsedBytes = monthlyMeteredBytes,
                onCapEnabledChange = viewModel::setMeteredCapEnabled,
                onCapMbChange = viewModel::setMeteredCapMb,
            )

            // Data & reset — cache/log clearing and full app reset.
            DataSection(
                onCacheClear = {
                    viewModel.clearCache(context) { ok ->
                        scope.launch { snackbarHostState.showSnackbar(if (ok) cacheClearedMsg else clearFailedMsg) }
                    }
                },
                onLogsClear = {
                    viewModel.clearLogs(context) { ok ->
                        scope.launch { snackbarHostState.showSnackbar(if (ok) logsClearedMsg else clearFailedMsg) }
                    }
                },
                onReset = {
                    viewModel.clearAppData(context) { success ->
                        if (!success) scope.launch { snackbarHostState.showSnackbar(resetFailedMsg) }
                    }
                },
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_section_help_about))
            SettingsLinkRow(
                label = stringResource(R.string.settings_item_your_stats),
                onClick = onOpenStats,
                leadingIcon = Icons.Outlined.BarChart,
            )
            // All app-identity / changelog / acknowledgements / reference links now
            // live on the dedicated About screen.
            SettingsLinkRow(
                label = stringResource(R.string.settings_item_about),
                onClick = onOpenAbout,
                leadingIcon = Icons.Outlined.Info,
            )
            // Bottom spacing so the last row clears the nav bar.
            Spacer(Modifier.padding(bottom = VirgaSpacing.sm))
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
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

/** Static label/placeholder/hint string resources for a [CommitOnBlurField]. */
private class FieldText(
    @get:StringRes val label: Int,
    @get:StringRes val placeholder: Int,
    @get:StringRes val hint: Int,
)

/**
 * An ASCII [OutlinedTextField] that fires [onCommit] once focus leaves it, but
 * only after it has been focused at least once (so the initial composition
 * doesn't spuriously commit). Both default-bandwidth inputs share this.
 */
@Composable
private fun CommitOnBlurField(
    value: String,
    onValueChange: (String) -> Unit,
    text: FieldText,
    onCommit: () -> Unit,
) {
    var hasBeenFocused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(text.label)) },
        placeholder = { Text(stringResource(text.placeholder)) },
        supportingText = { Text(stringResource(text.hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (state.isFocused) {
                    hasBeenFocused = true
                } else if (hasBeenFocused) {
                    onCommit()
                }
            },
        singleLine = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RetentionSection(days: Int, onDaysChange: (Int) -> Unit) {
    val options = listOf(0, 30, 90, 180, 365)
    val labels = mapOf(
        0 to R.string.settings_retention_forever,
        30 to R.string.settings_retention_30,
        90 to R.string.settings_retention_90,
        180 to R.string.settings_retention_180,
        365 to R.string.settings_retention_365,
    )
    var expanded by remember { mutableStateOf(false) }
    HorizontalDivider()
    SectionTitle(stringResource(R.string.settings_section_history))
    Text(
        stringResource(R.string.settings_retention_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(labels[days] ?: R.string.settings_retention_forever),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_retention_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(labels.getValue(option))) },
                    onClick = { onDaysChange(option); expanded = false },
                )
            }
        }
    }
}
