package app.lusk.virga.feature.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lusk.virga.core.designsystem.component.ToggleRow

/**
 * "Event triggers" section — three opt-in toggles that fire a sync when a
 * real-world event occurs (folder change, Wi-Fi connect, charger connect).
 *
 * All three require the watchdog foreground service to be active; they are
 * best-effort while the FGS runs, and have no effect if the watchdog is off.
 *
 * Grouped in the Battery & reliability section of [SettingsScreen], immediately
 * below the watchdog toggle.
 */
@Composable
internal fun EventTriggersSection(
    triggerOnFolderChange: Boolean,
    triggerOnWifiConnect: Boolean,
    triggerOnCharge: Boolean,
    onFolderChangeToggle: (Boolean) -> Unit,
    onWifiConnectToggle: (Boolean) -> Unit,
    onChargeToggle: (Boolean) -> Unit,
) {
    HorizontalDivider()
    SectionTitle(stringResource(R.string.settings_section_event_triggers))
    Text(
        stringResource(R.string.settings_event_triggers_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ToggleRow(
        label = stringResource(R.string.settings_toggle_trigger_folder),
        checked = triggerOnFolderChange,
        onChange = onFolderChangeToggle,
    )
    Text(
        stringResource(R.string.settings_trigger_folder_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ToggleRow(
        label = stringResource(R.string.settings_toggle_trigger_wifi),
        checked = triggerOnWifiConnect,
        onChange = onWifiConnectToggle,
    )
    Text(
        stringResource(R.string.settings_trigger_wifi_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ToggleRow(
        label = stringResource(R.string.settings_toggle_trigger_charge),
        checked = triggerOnCharge,
        onChange = onChargeToggle,
    )
    Text(
        stringResource(R.string.settings_trigger_charge_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
