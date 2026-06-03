package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.designsystem.component.VirgaCard
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * Snapshot of changelog information to show in the "What's new" banner on Home.
 * Produced by the app module (which owns BuildConfig) and passed as a parameter
 * so feature:sync has no BuildConfig dependency.
 */
data class ChangelogInfo(
    val versionName: String,
    val notes: List<String>,
)

/**
 * Snapshot of update availability to show in the update banner on Home.
 * An empty [versionLabel] is valid for Play (which doesn't expose a name).
 */
data class UpdateBanner(val versionLabel: String)

/**
 * Home dashboard — the app's landing tab (BRAND §10/§11). Shows the overall sync
 * status hero + lifetime stat-glance ([HomeHeader]), a primary "back up now" /
 * "create task" action, and quick links into Sync and Remotes.
 *
 * Banner params are all defaulted so call sites that don't wire update/changelog
 * logic continue to compile without changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenStats: () -> Unit,
    onAddTask: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenRemotes: () -> Unit,
    changelog: ChangelogInfo? = null,
    onDismissChangelog: () -> Unit = {},
    onViewChangelog: () -> Unit = {},
    updateAvailable: UpdateBanner? = null,
    onUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VirgaSpacing.md)
                .padding(bottom = VirgaSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md),
        ) {
            // Update and changelog banners appear at the very top, above the hero.
            if (updateAvailable != null) {
                UpdateBannerCard(
                    banner = updateAvailable,
                    onUpdate = onUpdate,
                    onDismiss = onDismissUpdate,
                )
            }
            if (changelog != null) {
                ChangelogCard(
                    info = changelog,
                    onDismiss = onDismissChangelog,
                    onView = onViewChangelog,
                )
            }

            HomeHeader(
                homeStatus = state.homeStatus,
                lifetimeBytes = state.lifetimeBytes,
                lifetimeRuns = state.lifetimeRuns,
                onOpenStats = onOpenStats,
            )

            // One primary action: back up everything if there are enabled tasks,
            // otherwise guide the user to create their first task (§11 one primary).
            if (state.hasEnabledTasks) {
                Button(onClick = { viewModel.backUpNow() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_action_backup_now))
                }
            } else {
                Button(onClick = onAddTask, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_action_create_task))
                }
            }

            HomeSummaryRow(
                icon = Icons.Filled.CloudSync,
                label = pluralStringResource(R.plurals.home_summary_tasks, state.taskCount, state.taskCount),
                onClick = onOpenSync,
            )
            HomeSummaryRow(
                icon = Icons.Filled.Storage,
                label = pluralStringResource(R.plurals.home_summary_remotes, state.remoteCount, state.remoteCount),
                onClick = onOpenRemotes,
            )
        }
    }
}

@Composable
private fun UpdateBannerCard(
    banner: UpdateBanner,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dismissCd = stringResource(R.string.home_banner_update_dismiss_cd)
    VirgaCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_banner_update_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (banner.versionLabel.isNotEmpty()) {
                    Text(
                        text = banner.versionLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = onUpdate,
                modifier = Modifier.semantics { role = Role.Button },
            ) {
                Text(stringResource(R.string.home_banner_update_action))
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = dismissCd },
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null, // row carries the description
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChangelogCard(
    info: ChangelogInfo,
    onDismiss: () -> Unit,
    onView: () -> Unit,
) {
    val dismissCd = stringResource(R.string.home_banner_changelog_dismiss_cd)
    VirgaCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.NewReleases,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp).padding(top = VirgaSpacing.xs),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_banner_changelog_title, info.versionName),
                    style = MaterialTheme.typography.titleSmall,
                )
                info.notes.forEach { note ->
                    Text(
                        text = "• $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = VirgaSpacing.xs),
                    )
                }
                TextButton(
                    onClick = onView,
                    modifier = Modifier.semantics { role = Role.Button },
                ) {
                    Text(stringResource(R.string.home_banner_changelog_view))
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = dismissCd },
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeSummaryRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    VirgaCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.md),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
