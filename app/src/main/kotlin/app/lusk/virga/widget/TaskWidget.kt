package app.lusk.virga.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.lusk.virga.R
import app.lusk.virga.core.common.model.SyncTask
import dagger.hilt.android.EntryPointAccessors

/** Preferences key for the task ID bound to a specific widget instance. */
internal val boundTaskIdKey = longPreferencesKey("bound_task_id")

/** Sentinel value meaning no task has been chosen yet for this widget instance. */
internal const val NO_TASK_ID = -1L

/**
 * Action parameter carrying the tapped widget's bound task ID into
 * [TaskSyncActionCallback]. Single source of truth — the composable that sets it
 * and the callback that reads it must use the same key or the param won't resolve.
 */
internal val taskIdActionKey = ActionParameters.Key<Long>("task_id")

// ---------------------------------------------------------------------------
// Pure display-logic helpers (unit-testable without Glance)
// ---------------------------------------------------------------------------

/**
 * Result of resolving widget display content from a possibly-bound task state.
 * Returned by [resolveTaskWidgetContent].
 */
internal sealed interface TaskWidgetContent {
    /** No task has been configured for this widget instance yet. */
    data object Unconfigured : TaskWidgetContent

    /** The previously-configured task was deleted (lookup succeeded, returned null). */
    data object TaskRemoved : TaskWidgetContent

    /** The task lookup failed transiently (DI/repository error) — distinct from deletion. */
    data object LoadFailed : TaskWidgetContent

    /** The task exists and can be rendered. */
    data class Ready(val taskId: Long, val name: String, val statusLine: String) : TaskWidgetContent
}

/**
 * Resolves widget content from a task [lookup] result. Distinguishes a transient
 * failure ([TaskWidgetContent.LoadFailed]) from a genuine deletion
 * ([TaskWidgetContent.TaskRemoved], lookup succeeded with null) so a momentary
 * repository/DI error is not mis-rendered as "task removed".
 *
 * Pure: no Glance, no coroutines, no Android APIs. Safe to unit test.
 */
internal fun resolveTaskWidgetContent(
    boundTaskId: Long,
    lookup: Result<SyncTask?>,
): TaskWidgetContent = when {
    boundTaskId == NO_TASK_ID -> TaskWidgetContent.Unconfigured
    lookup.isFailure -> TaskWidgetContent.LoadFailed
    else -> resolveTaskWidgetContent(boundTaskId, lookup.getOrNull())
}

/**
 * Resolves what the widget should display based on [boundTaskId] and the
 * [task] it resolves to (null means deleted or not found).
 *
 * Pure: no Glance, no coroutines, no Android APIs. Safe to unit test.
 */
internal fun resolveTaskWidgetContent(
    boundTaskId: Long,
    task: SyncTask?,
): TaskWidgetContent = when {
    boundTaskId == NO_TASK_ID -> TaskWidgetContent.Unconfigured
    task == null -> TaskWidgetContent.TaskRemoved
    else -> TaskWidgetContent.Ready(
        taskId = task.id,
        name = task.name,
        statusLine = formatTaskStatusLine(task),
    )
}

/**
 * Formats a one-line status description for a [SyncTask].
 *
 * Pure: no Android context required.
 */
internal fun formatTaskStatusLine(task: SyncTask): String {
    val state = if (task.enabled) "Enabled" else "Disabled"
    val direction = task.direction.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$state · $direction"
}

// ---------------------------------------------------------------------------
// GlanceAppWidget
// ---------------------------------------------------------------------------

class TaskWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val boundTaskId = prefs[boundTaskIdKey] ?: NO_TASK_ID

        val lookup: Result<SyncTask?> = if (boundTaskId != NO_TASK_ID) {
            runCatching {
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    VirgaWidgetEntryPoint::class.java,
                ).syncTaskRepository().getTask(boundTaskId)
            }
        } else {
            Result.success(null)
        }

        val content = resolveTaskWidgetContent(boundTaskId, lookup)
        val labels = TaskWidgetLabels(
            unconfigured = context.getString(R.string.widget_task_unconfigured),
            taskRemoved = context.getString(R.string.widget_task_removed),
            loadFailed = context.getString(R.string.widget_task_load_failed),
            backUp = context.getString(R.string.widget_task_back_up),
        )

        provideContent {
            GlanceTheme {
                TaskWidgetContent(content = content, labels = labels)
            }
        }
    }
}

/** Pre-resolved widget strings, grouped to keep composable param counts under detekt's limit. */
private data class TaskWidgetLabels(
    val unconfigured: String,
    val taskRemoved: String,
    val loadFailed: String,
    val backUp: String,
)

@Composable
private fun TaskWidgetContent(
    content: TaskWidgetContent,
    labels: TaskWidgetLabels,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        when (content) {
            is TaskWidgetContent.Unconfigured -> PlaceholderText(labels.unconfigured)
            is TaskWidgetContent.TaskRemoved -> PlaceholderText(labels.taskRemoved)
            is TaskWidgetContent.LoadFailed -> PlaceholderText(labels.loadFailed)
            is TaskWidgetContent.Ready -> ReadyContent(content, labels.backUp)
        }
    }
}

@Composable
private fun PlaceholderText(label: String) {
    Text(
        text = label,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 13.sp,
        ),
        modifier = GlanceModifier.fillMaxWidth(),
    )
}

@Composable
private fun ReadyContent(content: TaskWidgetContent.Ready, backUpLabel: String) {
    Text(
        text = content.name,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    Spacer(modifier = GlanceModifier.height(4.dp))
    Text(
        text = content.statusLine,
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = 13.sp,
        ),
        modifier = GlanceModifier.fillMaxWidth(),
    )
    Spacer(modifier = GlanceModifier.height(8.dp))
    Button(
        text = backUpLabel,
        onClick = actionRunCallback<TaskSyncActionCallback>(
            actionParametersOf(taskIdActionKey to content.taskId),
        ),
        modifier = GlanceModifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------
// ActionCallback
// ---------------------------------------------------------------------------

/** Triggers an immediate sync of the bound task when the widget button is tapped. */
class TaskSyncActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[taskIdActionKey]
            ?: run {
                val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
                prefs[boundTaskIdKey]
            }
            ?: NO_TASK_ID

        // Reject both the NO_TASK_ID sentinel (-1) and the unsaved-row id 0 (Room
        // autogen ids start at 1), so we never enqueue a sync for a non-task.
        if (taskId <= 0L) return

        val scheduler = EntryPointAccessors.fromApplication(
            context.applicationContext,
            VirgaWidgetEntryPoint::class.java,
        ).syncScheduler()

        runCatching { scheduler.syncNow(taskId) }
            .onFailure { Log.w(TAG, "syncNow failed for taskId=$taskId", it) }
    }

    private companion object {
        const val TAG = "TaskWidget"
    }
}

// ---------------------------------------------------------------------------
// Receiver
// ---------------------------------------------------------------------------

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}
