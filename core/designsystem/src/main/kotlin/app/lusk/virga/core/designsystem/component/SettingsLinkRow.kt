package app.lusk.virga.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * A full-width, tappable settings / navigation row (BRAND §6, §11, §14).
 *
 * Shared design-system primitive so feature modules don't hand-roll list rows
 * out of raw `TextButton`s (which render accent-coloured, centred text — the
 * wrong affordance for a list). Renders a neutral `onSurface` headline, an
 * optional leading icon, and — for rows that leave the app — a trailing
 * "open in new" affordance so a browser hand-off is signalled before the tap.
 * The whole row is one clickable node carrying [Role.Button], with a ≥48dp
 * minimum height for touch targets.
 *
 * @param opensExternally when true, shows the trailing external-link glyph and
 *   exposes [externalLinkDescription] to accessibility services. Leave false for
 *   rows that open an in-app dialog/sheet — the absence of the glyph is itself
 *   the "stays in the app" signal.
 */
@Composable
fun SettingsLinkRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    opensExternally: Boolean = false,
    externalLinkDescription: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = VirgaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.md),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null, // decorative — the label carries the meaning
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (opensExternally) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = externalLinkDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        } else {
            // A chevron on in-app rows gives every row a trailing affordance; the
            // open-in-new glyph vs. chevron then signals "leaves the app" vs. "stays".
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
