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
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.designsystem.component.VirgaCard
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * Home dashboard — the app's landing tab (BRAND §10/§11). Shows the overall sync
 * status hero + lifetime stat-glance ([HomeHeader]), a primary "back up now" /
 * "create task" action, and quick links into Sync and Remotes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenStats: () -> Unit,
    onAddTask: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenRemotes: () -> Unit,
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
