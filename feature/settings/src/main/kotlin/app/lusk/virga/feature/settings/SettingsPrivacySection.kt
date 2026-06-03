package app.lusk.virga.feature.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lusk.virga.core.designsystem.component.ToggleRow

/**
 * Privacy section — the opt-in crash-reporting toggle. Extracted from SettingsScreen to
 * keep that file under the 500-line limit. The caller gates visibility on whether a
 * crash-reporting backend (Sentry DSN) is configured for the build, so this is only
 * shown when toggling it would actually do something. Default OFF (see AppPreferences).
 */
@Composable
internal fun PrivacySection(enabled: Boolean, onChange: (Boolean) -> Unit) {
    HorizontalDivider()
    SectionTitle(stringResource(R.string.settings_section_privacy))
    ToggleRow(
        label = stringResource(R.string.settings_toggle_crash_reporting),
        checked = enabled,
        onChange = onChange,
    )
    Text(
        stringResource(R.string.settings_crash_reporting_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
