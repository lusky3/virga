package app.lusk.virga.feature.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lusk.virga.core.designsystem.component.ToggleRow

/**
 * Security section — the opt-in app-lock toggle. Extracted from SettingsScreen to
 * keep that file under the 500-line limit.
 *
 * The toggle gates ONLY the UI presentation. The sync worker and foreground service
 * keep running while the app is locked. It does NOT add encryption beyond what rclone
 * already applies to its config at rest — the hint surface this honestly to the user.
 */
@Composable
internal fun SecuritySection(appLockEnabled: Boolean, onAppLockChange: (Boolean) -> Unit) {
    HorizontalDivider()
    SectionTitle(stringResource(R.string.settings_section_security))
    ToggleRow(
        label = stringResource(R.string.settings_toggle_app_lock),
        checked = appLockEnabled,
        onChange = onAppLockChange,
    )
    Text(
        stringResource(R.string.settings_app_lock_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
