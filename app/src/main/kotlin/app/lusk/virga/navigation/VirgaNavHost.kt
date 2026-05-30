package app.lusk.virga.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.lusk.virga.R
import app.lusk.virga.feature.explorer.FileBrowserScreen
import app.lusk.virga.feature.remotes.RemotesScreen
import app.lusk.virga.feature.settings.SettingsScreen
import app.lusk.virga.feature.sync.ConflictsScreen
import app.lusk.virga.feature.sync.RunDetailScreen
import app.lusk.virga.feature.sync.SyncHistoryScreen
import app.lusk.virga.feature.sync.SyncTaskEditScreen
import app.lusk.virga.feature.sync.SyncTasksScreen
import kotlinx.serialization.Serializable

/** Type-safe Navigation 3 routes. Each is a [NavKey] so it can live in a back stack. */
@Serializable object SyncRoute : NavKey
@Serializable object RemotesRoute : NavKey
@Serializable object SettingsRoute : NavKey

/**
 * Edit/create a sync task. [taskId] == 0 means "new task". [prefillRemote] and
 * [prefillRemotePath] let the file browser seed the remote fields when the user
 * taps "Sync this folder" — they are null for plain create/edit flows.
 */
@Serializable data class TaskEditRoute(
    val taskId: Long,
    val prefillRemote: String? = null,
    val prefillRemotePath: String? = null,
) : NavKey

@Serializable object HistoryRoute : NavKey
@Serializable data class BrowseRoute(val remoteName: String? = null) : NavKey
@Serializable object ConflictsRoute : NavKey

/** Detail view for a completed sync run. */
@Serializable data class RunDetailRoute(val runId: Long) : NavKey

private data class TopLevel(
    val route: NavKey,
    val labelRes: Int,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevel(SyncRoute, R.string.nav_tab_sync, Icons.Filled.CloudSync),
    TopLevel(RemotesRoute, R.string.nav_tab_remotes, Icons.Filled.Storage),
    TopLevel(SettingsRoute, R.string.nav_tab_settings, Icons.Filled.Settings),
)

/**
 * Root navigation host.
 *
 * ia-05: Uses [NavigationSuiteScaffold] so the navigation chrome automatically
 * shifts between a bottom bar on compact widths and a navigation rail on
 * medium/expanded widths (adaptive info is resolved transitively).
 *
 * ia-06: Tab reselect (handled in [Navigator.navigate]) pops the active tab's
 * back stack to its root entry.
 */
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
                prefillRemote = key.prefillRemote,
                prefillRemotePath = key.prefillRemotePath,
                onBack = dropUnlessResumed { navigator.goBack() },
                onNavigateToRemotes = dropUnlessResumed { navigator.navigate(RemotesRoute) },
            )
        }
        entry<HistoryRoute> {
            SyncHistoryScreen(
                onBack = dropUnlessResumed { navigator.goBack() },
                onOpenRun = { id -> navigator.navigate(RunDetailRoute(id)) },
            )
        }
        entry<RunDetailRoute> { key ->
            RunDetailScreen(
                runId = key.runId,
                onBack = dropUnlessResumed { navigator.goBack() },
            )
        }
        entry<ConflictsRoute> {
            ConflictsScreen(onBack = dropUnlessResumed { navigator.goBack() })
        }
        entry<RemotesRoute> {
            RemotesScreen(
                onOpenBrowser = { r -> navigator.navigate(BrowseRoute(r)) },
                onCreateTask = dropUnlessResumed { navigator.navigate(TaskEditRoute(0)) },
            )
        }
        entry<BrowseRoute> { key ->
            FileBrowserScreen(
                initialRemote = key.remoteName,
                onBack = dropUnlessResumed { navigator.goBack() },
                onNavigateToRemotes = dropUnlessResumed { navigator.navigate(RemotesRoute) },
                onSyncFolder = { remote, path ->
                    navigator.navigate(TaskEditRoute(0, prefillRemote = remote, prefillRemotePath = path))
                },
            )
        }
        entry<SettingsRoute> { SettingsScreen() }
    }

    // Resolve tab labels in the composable scope; the navigationSuiteItems
    // builder lambda below is not a @Composable context.
    val tabLabels = topLevelDestinations.map { stringResource(it.labelRes) }

    // Back behaviour: NavDisplay's onBack pops a child or, at a non-home tab root,
    // switches to the home tab. Only when we are at the home tab root (nothing to
    // pop) do we run the "press back again to exit" flow, gated behind its own
    // BackHandler so it never competes with NavDisplay's in-stack back handling.
    val context = LocalContext.current
    val exitMessage = stringResource(R.string.exit_confirm_toast)
    var lastBackMs by remember { mutableLongStateOf(0L) }
    val atHomeRoot = navigationState.topLevelRoute == navigationState.startRoute &&
        (navigationState.backStacks[navigationState.startRoute]?.size ?: 1) == 1

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            topLevelDestinations.forEachIndexed { index, dest ->
                item(
                    selected = dest.route == navigationState.topLevelRoute,
                    onClick = { navigator.navigate(dest.route) },
                    icon = { Icon(dest.icon, contentDescription = null) },
                    label = { Text(tabLabels[index]) },
                )
            }
        },
    ) {
        NavDisplay(
            entries = navigationState.toEntries(entryProvider),
            onBack = { navigator.goBack() },
        )
        // Double-tap-to-exit, active only at the home tab root.
        BackHandler(enabled = atHomeRoot) {
            val now = System.currentTimeMillis()
            if (now - lastBackMs < EXIT_CONFIRM_WINDOW_MS) {
                (context as? Activity)?.finish()
            } else {
                lastBackMs = now
                Toast.makeText(context, exitMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Window within which a second back press at the home root exits the app. */
private const val EXIT_CONFIRM_WINDOW_MS = 2_000L
