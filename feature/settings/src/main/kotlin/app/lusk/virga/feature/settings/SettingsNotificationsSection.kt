package app.lusk.virga.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lusk.virga.core.designsystem.component.SettingsLinkRow
import app.lusk.virga.core.designsystem.component.ToggleRow

/**
 * Notifications section — a toggle to suppress clean-success notifications and a
 * deep-link into the OS channel settings. Extracted from SettingsScreen to keep
 * that file under the 500-line limit.
 *
 * Only the success-completion notification is ever suppressed; the foreground/
 * progress notification and all error/failure notifications always post regardless
 * of the toggle state (enforced in SyncWorker).
 */
@Composable
internal fun NotificationsSection(
    notifyOnFailureOnly: Boolean,
    onNotifyOnFailureOnlyChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    HorizontalDivider()
    SectionTitle(stringResource(R.string.settings_section_notifications))
    ToggleRow(
        label = stringResource(R.string.settings_toggle_notify_on_failure_only),
        checked = notifyOnFailureOnly,
        onChange = onNotifyOnFailureOnlyChange,
    )
    Text(
        stringResource(R.string.settings_notify_on_failure_only_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsLinkRow(
        label = stringResource(R.string.settings_item_notification_settings),
        onClick = {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                // No notification settings activity on this device; silently ignore.
            }
        },
    )
}
