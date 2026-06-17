package app.lusk.virga.feature.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lusk.virga.core.designsystem.component.ToggleRow

/** Which of the three event-trigger toggles fired. */
enum class EventTriggerKind { FOLDER_CHANGE, WIFI_CONNECT, CHARGE }

/**
 * Snapshot of the three event-trigger toggle states, passed as a single
 * parameter to [EventTriggersSection] to stay within detekt's LongParameterList
 * threshold.
 */
data class EventTriggerState(
    val folderChange: Boolean = false,
    val wifiConnect: Boolean = false,
    val charge: Boolean = false,
)

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
    state: EventTriggerState,
    onToggle: (EventTriggerKind, Boolean) -> Unit,
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
        checked = state.folderChange,
        onChange = { onToggle(EventTriggerKind.FOLDER_CHANGE, it) },
    )
    Text(
        stringResource(R.string.settings_trigger_folder_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ToggleRow(
        label = stringResource(R.string.settings_toggle_trigger_wifi),
        checked = state.wifiConnect,
        onChange = { onToggle(EventTriggerKind.WIFI_CONNECT, it) },
    )
    Text(
        stringResource(R.string.settings_trigger_wifi_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ToggleRow(
        label = stringResource(R.string.settings_toggle_trigger_charge),
        checked = state.charge,
        onChange = { onToggle(EventTriggerKind.CHARGE, it) },
    )
    Text(
        stringResource(R.string.settings_trigger_charge_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
