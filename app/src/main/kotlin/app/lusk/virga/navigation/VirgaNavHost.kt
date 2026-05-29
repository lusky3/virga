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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.lusk.virga.feature.explorer.FileBrowserScreen
import app.lusk.virga.feature.remotes.RemotesScreen
import app.lusk.virga.feature.settings.SettingsScreen
import app.lusk.virga.feature.sync.ConflictsScreen
import app.lusk.virga.feature.sync.SyncHistoryScreen
import app.lusk.virga.feature.sync.SyncTaskEditScreen
import app.lusk.virga.feature.sync.SyncTasksScreen
import kotlinx.serialization.Serializable

/** Type-safe navigation routes. */
@Serializable object SyncRoute
@Serializable object RemotesRoute
@Serializable object SettingsRoute
@Serializable data class TaskEditRoute(val taskId: Long)
@Serializable object HistoryRoute
@Serializable object BrowseRoute
@Serializable object ConflictsRoute

private data class TopLevel(
    val route: Any,
    val label: String,
    val icon: ImageVector,
    val matches: (androidx.navigation.NavDestination?) -> Boolean,
)

private val topLevelDestinations = listOf(
    TopLevel(SyncRoute, "Sync", Icons.Filled.CloudSync) { it?.hasRoute(SyncRoute::class) == true },
    TopLevel(RemotesRoute, "Remotes", Icons.Filled.Storage) { it?.hasRoute(RemotesRoute::class) == true },
    TopLevel(SettingsRoute, "Settings", Icons.Filled.Settings) { it?.hasRoute(SettingsRoute::class) == true },
)

@Composable
fun VirgaNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                topLevelDestinations.forEach { dest ->
                    NavigationBarItem(
                        selected = dest.matches(currentDestination),
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = SyncRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable<SyncRoute> {
                SyncTasksScreen(
                    onAddTask = { navController.navigate(TaskEditRoute(0)) },
                    onEditTask = { id -> navController.navigate(TaskEditRoute(id)) },
                    onOpenHistory = { navController.navigate(HistoryRoute) },
                    onOpenConflicts = { navController.navigate(ConflictsRoute) },
                )
            }
            composable<TaskEditRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<TaskEditRoute>()
                SyncTaskEditScreen(
                    taskId = route.taskId,
                    onBack = { navController.popBackStack() },
                    onNavigateToRemotes = {
                        navController.navigate(RemotesRoute) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable<HistoryRoute> {
                SyncHistoryScreen(onBack = { navController.popBackStack() })
            }
            composable<ConflictsRoute> {
                ConflictsScreen(onBack = { navController.popBackStack() })
            }
            composable<RemotesRoute> {
                RemotesScreen(onOpenBrowser = { navController.navigate(BrowseRoute) })
            }
            composable<BrowseRoute> {
                FileBrowserScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToRemotes = {
                        navController.navigate(RemotesRoute) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable<SettingsRoute> { SettingsScreen() }
        }
    }
}
