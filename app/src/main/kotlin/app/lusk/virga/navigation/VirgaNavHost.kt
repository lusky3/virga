package app.lusk.virga.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.lusk.virga.core.designsystem.theme.LocalSharedTransitionScope
import app.lusk.virga.core.designsystem.theme.VirgaMotion
import app.lusk.virga.core.designsystem.theme.rememberReduceMotion
import app.lusk.virga.R
import app.lusk.virga.feature.explorer.FileBrowserScreen
import app.lusk.virga.feature.remotes.RemotesScreen
import app.lusk.virga.feature.settings.SettingsScreen
import app.lusk.virga.feature.sync.ConflictsScreen
import app.lusk.virga.feature.sync.FirstSyncWizardScreen
import app.lusk.virga.feature.sync.LogViewerScreen
import app.lusk.virga.feature.sync.RunDetailScreen
import app.lusk.virga.feature.sync.SyncHistoryScreen
import app.lusk.virga.feature.sync.SyncTaskEditScreen
import app.lusk.virga.feature.sync.SyncTaskSummaryScreen
import app.lusk.virga.feature.sync.SyncTasksAdaptiveScreen
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
@Serializable data class BrowseRoute(
    val remoteName: String? = null,
    /** Opened as a destination-folder picker for the task editor (vs. plain browsing). */
    val pick: Boolean = false,
) : NavKey
@Serializable object ConflictsRoute : NavKey

/** Read-only overview of a task (tapping a task row lands here, not in the editor). */
@Serializable data class TaskSummaryRoute(val taskId: Long) : NavKey

/** Detail view for a completed sync run. */
@Serializable data class RunDetailRoute(val runId: Long) : NavKey

/** Per-run log viewer (WS2.5). */
@Serializable data class LogViewerRoute(val logPath: String) : NavKey

/** Guided first-sync wizard for cold-install users (WS1.2). */
@Serializable object FirstSyncWizardRoute : NavKey

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
 *
 * [startAtWizard] — when true (set after first-run onboarding), the Sync tab
 * starts with [FirstSyncWizardRoute] on the stack so the user lands directly
 * in the guided setup wizard.
 */
@Composable
fun VirgaNavHost(startAtWizard: Boolean = false) {
    val navigationState = rememberNavigationState(
        startRoute = SyncRoute,
        topLevelRoutes = setOf(SyncRoute, RemotesRoute, SettingsRoute),
    )
    val navigator = remember { Navigator(navigationState) }

    // Push the wizard onto the Sync stack once, on first composition, when
    // the caller requests it (i.e. coming fresh from onboarding completion).
    androidx.compose.runtime.LaunchedEffect(startAtWizard) {
        if (startAtWizard) navigator.navigate(FirstSyncWizardRoute)
    }

    // The Sync tab's adaptive list-detail scaffold can have its own internal back
    // state (detail pane open on a single-pane width). While it can navigate back,
    // its own BackHandler must win — so the exit-toast handler below stands down to
    // avoid the two competing at the home root (WS3.5).
    var syncDetailCanGoBack by remember { mutableStateOf(false) }

    val entryProvider = entryProvider {
        entry<SyncRoute> {
            SyncTasksAdaptiveScreen(
                onAddTask = dropUnlessResumed { navigator.navigate(TaskEditRoute(0)) },
                onStartWizard = dropUnlessResumed { navigator.navigate(FirstSyncWizardRoute) },
                onOpenTask = { id -> navigator.navigate(TaskSummaryRoute(id)) },
                onEditTask = { id -> navigator.navigate(TaskEditRoute(id)) },
                onOpenHistory = dropUnlessResumed { navigator.navigate(HistoryRoute) },
                onOpenConflicts = dropUnlessResumed { navigator.navigate(ConflictsRoute) },
                onOpenRun = { id -> navigator.navigate(RunDetailRoute(id)) },
                onDetailBackAvailableChanged = { syncDetailCanGoBack = it },
            )
        }
        entry<FirstSyncWizardRoute> {
            FirstSyncWizardScreen(
                onBack = dropUnlessResumed { navigator.goBack() },
                onNavigateToRemotes = dropUnlessResumed { navigator.navigate(RemotesRoute) },
                onFinished = { taskId ->
                    // Pop the completed wizard first so Back from the summary lands
                    // on the task list, not a blank finished wizard pane.
                    navigator.goBack()
                    navigator.navigate(TaskSummaryRoute(taskId))
                },
            )
        }
        entry<TaskSummaryRoute> { key ->
            SyncTaskSummaryScreen(
                taskId = key.taskId,
                onBack = dropUnlessResumed { navigator.goBack() },
                onEdit = { id -> navigator.navigate(TaskEditRoute(id)) },
                onOpenRun = { id -> navigator.navigate(RunDetailRoute(id)) },
            )
        }
        entry<TaskEditRoute> { key ->
            SyncTaskEditScreen(
                taskId = key.taskId,
                prefillRemote = key.prefillRemote,
                prefillRemotePath = key.prefillRemotePath,
                onBack = dropUnlessResumed { navigator.goBack() },
                onNavigateToRemotes = dropUnlessResumed { navigator.navigate(RemotesRoute) },
                onBrowseDestination = { remoteName ->
                    navigator.navigate(BrowseRoute(remoteName = remoteName, pick = true))
                },
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
                onViewLog = { path -> navigator.navigate(LogViewerRoute(path)) },
            )
        }
        entry<LogViewerRoute> { key ->
            LogViewerScreen(
                logPath = key.logPath,
                onBack = dropUnlessResumed { navigator.goBack() },
            )
        }
        entry<ConflictsRoute> {
            ConflictsScreen(onBack = dropUnlessResumed { navigator.goBack() })
        }
        entry<RemotesRoute> {
            RemotesScreen(
                onOpenBrowser = { r -> navigator.navigate(BrowseRoute(r)) },
                onCreateTask = { remoteName -> navigator.navigate(TaskEditRoute(0, prefillRemote = remoteName)) },
            )
        }
        entry<BrowseRoute> { key ->
            FileBrowserScreen(
                initialRemote = key.remoteName,
                pickMode = key.pick,
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
        // Shared X-axis nav motion + predictive back (BRAND §12). Falls back to a
        // plain crossfade when the system "remove animations" setting is on.
        val reduceMotion = rememberReduceMotion()
        @OptIn(ExperimentalSharedTransitionApi::class)
        SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        NavDisplay(
            entries = navigationState.toEntries(entryProvider),
            onBack = { navigator.goBack() },
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(VirgaMotion.navTween()) togetherWith fadeOut(VirgaMotion.navTween())
                } else {
                    (slideInHorizontally(VirgaMotion.navTween()) { it / 4 } + fadeIn(VirgaMotion.navTween())) togetherWith
                        (slideOutHorizontally(VirgaMotion.navTween()) { -it / 4 } + fadeOut(VirgaMotion.navTween()))
                }
            },
            popTransitionSpec = {
                if (reduceMotion) {
                    fadeIn(VirgaMotion.navTween()) togetherWith fadeOut(VirgaMotion.navTween())
                } else {
                    (slideInHorizontally(VirgaMotion.navTween()) { -it / 4 } + fadeIn(VirgaMotion.navTween())) togetherWith
                        (slideOutHorizontally(VirgaMotion.navTween()) { it / 4 } + fadeOut(VirgaMotion.navTween()))
                }
            },
            predictivePopTransitionSpec = {
                if (reduceMotion) {
                    fadeIn(VirgaMotion.navTween()) togetherWith fadeOut(VirgaMotion.navTween())
                } else {
                    (slideInHorizontally(VirgaMotion.navTween()) { -it / 4 } + fadeIn(VirgaMotion.navTween())) togetherWith
                        (slideOutHorizontally(VirgaMotion.navTween()) { it / 4 } + fadeOut(VirgaMotion.navTween()))
                }
            },
        )
        } // CompositionLocalProvider
        } // SharedTransitionLayout
        // Double-tap-to-exit, active only at the home tab root — and only when the
        // Sync adaptive scaffold has no internal back of its own to handle (WS3.5).
        BackHandler(enabled = atHomeRoot && !syncDetailCanGoBack) {
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
