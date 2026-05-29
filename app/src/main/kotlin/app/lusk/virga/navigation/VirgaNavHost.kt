package app.lusk.virga.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.lusk.virga.feature.explorer.FileBrowserScreen
import app.lusk.virga.feature.remotes.RemotesScreen
import app.lusk.virga.feature.settings.SettingsScreen
import app.lusk.virga.feature.sync.ConflictsScreen
import app.lusk.virga.feature.sync.SyncHistoryScreen
import app.lusk.virga.feature.sync.SyncTaskEditScreen
import app.lusk.virga.feature.sync.SyncTasksScreen
import kotlinx.serialization.Serializable

/** Type-safe Navigation 3 routes. Each is a [NavKey] so it can live in a back stack. */
@Serializable object SyncRoute : NavKey
@Serializable object RemotesRoute : NavKey
@Serializable object SettingsRoute : NavKey
@Serializable data class TaskEditRoute(val taskId: Long) : NavKey
@Serializable object HistoryRoute : NavKey
@Serializable object BrowseRoute : NavKey
@Serializable object ConflictsRoute : NavKey

private data class TopLevel(val route: NavKey, val label: String, val icon: ImageVector)

private val topLevelDestinations = listOf(
    TopLevel(SyncRoute, "Sync", Icons.Filled.CloudSync),
    TopLevel(RemotesRoute, "Remotes", Icons.Filled.Storage),
    TopLevel(SettingsRoute, "Settings", Icons.Filled.Settings),
)

@Composable
fun VirgaNavHost() {
    val navigationState = rememberNavigationState(
        startRoute = SyncRoute,
        topLevelRoutes = setOf(SyncRoute, RemotesRoute, SettingsRoute),
    )
    val navigator = remember { Navigator(navigationState) }

    val entryProvider = entryProvider {
        entry<SyncRoute> {
            SyncTasksScreen(
                onAddTask = dropUnlessResumed { navigator.navigate(TaskEditRoute(0)) },
                onEditTask = { id -> navigator.navigate(TaskEditRoute(id)) },
                onOpenHistory = dropUnlessResumed { navigator.navigate(HistoryRoute) },
                onOpenConflicts = dropUnlessResumed { navigator.navigate(ConflictsRoute) },
            )
        }
        entry<TaskEditRoute> { key ->
            SyncTaskEditScreen(
                taskId = key.taskId,
                onBack = dropUnlessResumed { navigator.goBack() },
                onNavigateToRemotes = dropUnlessResumed { navigator.navigate(RemotesRoute) },
            )
        }
        entry<HistoryRoute> {
            SyncHistoryScreen(onBack = dropUnlessResumed { navigator.goBack() })
        }
        entry<ConflictsRoute> {
            ConflictsScreen(onBack = dropUnlessResumed { navigator.goBack() })
        }
        entry<RemotesRoute> {
            RemotesScreen(onOpenBrowser = dropUnlessResumed { navigator.navigate(BrowseRoute) })
        }
        entry<BrowseRoute> {
            FileBrowserScreen(
                onBack = dropUnlessResumed { navigator.goBack() },
                onNavigateToRemotes = dropUnlessResumed { navigator.navigate(RemotesRoute) },
            )
        }
        entry<SettingsRoute> { SettingsScreen() }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                topLevelDestinations.forEach { dest ->
                    NavigationBarItem(
                        selected = dest.route == navigationState.topLevelRoute,
                        onClick = { navigator.navigate(dest.route) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavDisplay(
            entries = navigationState.toEntries(entryProvider),
            onBack = { navigator.goBack() },
            modifier = Modifier.padding(padding),
        )
    }
}
