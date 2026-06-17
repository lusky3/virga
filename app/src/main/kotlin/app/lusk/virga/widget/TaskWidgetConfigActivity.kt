package app.lusk.virga.widget

import android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID
import android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import app.lusk.virga.R
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.designsystem.component.SettingsLinkRow
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class TaskWidgetConfigViewModel @Inject constructor(
    private val repo: SyncTaskRepository,
) : ViewModel() {

    val tasks: StateFlow<List<SyncTask>> = repo.tasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

/**
 * Standard AppWidget configuration activity shown by the launcher when the user
 * places a per-task TaskWidget on the home screen.
 *
 * SEC: exported is required because the launcher starts this activity during
 * widget placement; it only reads the user's own task list and binds a taskId
 * — no privileged surface, no caller-supplied params.
 */
@AndroidEntryPoint
class TaskWidgetConfigActivity : AppCompatActivity() {

    private val viewModel: TaskWidgetConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Per AppWidget contract: set RESULT_CANCELED first so backing out = cancel.
        setResult(RESULT_CANCELED)

        val appWidgetId = intent.extras?.getInt(EXTRA_APPWIDGET_ID, INVALID_APPWIDGET_ID)
            ?: INVALID_APPWIDGET_ID
        if (appWidgetId == INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            VirgaTheme {
                val tasks by viewModel.tasks.collectAsStateWithLifecycle()
                TaskWidgetConfigScreen(
                    tasks = tasks,
                    onTaskPicked = { taskId: Long -> bindTaskAndFinish(appWidgetId, taskId) },
                    onDismiss = ::finish,
                )
            }
        }
    }

    private fun bindTaskAndFinish(appWidgetId: Int, taskId: Long) {
        lifecycleScope.launch {
            // getGlanceIdBy throws IllegalArgumentException if the widget was removed
            // between placement and selection. Guard the whole bind so we always
            // finish() — the initial RESULT_CANCELED then takes effect on failure.
            runCatching {
                val glanceManager = GlanceAppWidgetManager(applicationContext)
                val glanceId = glanceManager.getGlanceIdBy(appWidgetId)
                updateAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                    prefs.toMutablePreferences().apply { this[boundTaskIdKey] = taskId }
                }
                TaskWidget().update(applicationContext, glanceId)
                setResult(RESULT_OK, Intent().putExtra(EXTRA_APPWIDGET_ID, appWidgetId))
            }.onFailure { Log.w("TaskWidgetConfig", "Failed to bind widget $appWidgetId", it) }
            finish()
        }
    }
}

// ---------------------------------------------------------------------------
// Composable screen + callbacks holder
// ---------------------------------------------------------------------------

/**
 * Callbacks data class keeps [TaskWidgetConfigScreen]'s param count under 6
 * (detekt LongParameterList). Rename-safe alternative to a same-named composable
 * that would conflict with the data class constructor in Kotlin's overload resolution.
 */
data class TaskWidgetConfigCallbacks(
    val onTaskPicked: (Long) -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
fun TaskWidgetConfigScreen(
    tasks: List<SyncTask>,
    onTaskPicked: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val callbacks = TaskWidgetConfigCallbacks(onTaskPicked, onDismiss)
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.widget_task_config_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (tasks.isEmpty()) {
            EmptyTasksContent(onDismiss = callbacks.onDismiss)
        } else {
            TaskList(tasks = tasks, onTaskPicked = callbacks.onTaskPicked)
        }
    }
}

@Composable
private fun TaskList(tasks: List<SyncTask>, onTaskPicked: (Long) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(tasks, key = { it.id }) { task ->
            SettingsLinkRow(
                label = task.name,
                onClick = { onTaskPicked(task.id) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun EmptyTasksContent(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.widget_task_config_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.widget_task_config_dismiss))
            }
        }
    }
}
