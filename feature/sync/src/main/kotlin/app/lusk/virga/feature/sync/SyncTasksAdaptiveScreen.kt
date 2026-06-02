package app.lusk.virga.feature.sync

import androidx.activity.compose.BackHandler
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * Adaptive list-detail wrapper for the Sync tab (WS3.5).
 *
 * On compact widths (phones, folded foldables) the scaffold shows a single
 * pane. Tapping a task delegates to [onOpenTask] (the outer Navigator) so the
 * existing full-screen TaskSummaryRoute is used — no regression.
 *
 * On medium/expanded widths (tablets, unfolded foldables) tapping a task
 * navigates the scaffold into the detail pane, showing [SyncTaskSummaryScreen]
 * alongside the list. Back collapses back to the list pane.
 *
 * Edit / run-detail actions from inside the detail pane are forwarded through
 * [onEditTask] / [onOpenRun] so the outer navigator handles them as before.
 *
 * Out of scope (deferred): RemotesScreen and FileBrowserScreen two-pane
 * layouts remain single-pane; they are not touched here.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SyncTasksAdaptiveScreen(
    onAddTask: () -> Unit,
    onStartWizard: () -> Unit = {},
    onOpenTask: (Long) -> Unit,
    onEditTask: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenRun: (Long) -> Unit,
    onOpenStats: () -> Unit = {},
    /** Reports whether the scaffold currently has its own back to handle, so the
     *  host can stand its exit handler down and avoid a back-handler clash (WS3.5). */
    onDetailBackAvailableChanged: (Boolean) -> Unit = {},
) {
    val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator<Long>()
    val scope = rememberCoroutineScope()

    val isExpanded = scaffoldNavigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] ==
        PaneAdaptedValue.Expanded

    // Persist the selected task id across recompositions so the detail pane
    // content survives configuration changes.
    var selectedTaskId by rememberSaveable { mutableLongStateOf(0L) }

    // Collapse the detail pane when back is pressed while the scaffold can
    // navigate back (only relevant on compact single-pane mode).
    val canNavigateBack = scaffoldNavigator.canNavigateBack()
    LaunchedEffect(canNavigateBack) { onDetailBackAvailableChanged(canNavigateBack) }
    BackHandler(enabled = canNavigateBack) {
        scope.launch { scaffoldNavigator.navigateBack() }
    }

    ListDetailPaneScaffold(
        directive = scaffoldNavigator.scaffoldDirective,
        value = scaffoldNavigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                SyncTasksScreen(
                    onAddTask = onAddTask,
                    onStartWizard = onStartWizard,
                    onOpenTask = { id ->
                        if (isExpanded) {
                            selectedTaskId = id
                            scope.launch {
                                scaffoldNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id)
                            }
                        } else {
                            onOpenTask(id)
                        }
                    },
                    onEditTask = onEditTask,
                    onOpenHistory = onOpenHistory,
                    onOpenConflicts = onOpenConflicts,
                    onOpenStats = onOpenStats,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val taskId = scaffoldNavigator.currentDestination?.contentKey ?: selectedTaskId
                if (taskId != 0L) {
                    SyncTaskSummaryScreen(
                        taskId = taskId,
                        onBack = { scope.launch { scaffoldNavigator.navigateBack() } },
                        onEdit = onEditTask,
                        onOpenRun = onOpenRun,
                    )
                }
            }
        },
    )
}
