package app.lusk.virga.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.lusk.virga.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

class SyncWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            VirgaWidgetEntryPoint::class.java,
        )
        val repo = entryPoint.syncTaskRepository()

        val tasks = runCatching { repo.tasks.first() }.getOrDefault(emptyList())
        val enabledCount = tasks.count { it.enabled }
        val totalCount = tasks.size

        val summary = when {
            totalCount == 0 -> context.getString(R.string.widget_no_tasks)
            else -> context.resources.getQuantityString(
                R.plurals.widget_task_summary,
                enabledCount,
                enabledCount,
                totalCount,
            )
        }
        val backUpNowLabel = context.getString(R.string.widget_back_up_now)

        provideContent {
            GlanceTheme {
                WidgetContent(summary = summary, backUpNowLabel = backUpNowLabel)
            }
        }
    }
}

@Composable
private fun WidgetContent(summary: String, backUpNowLabel: String) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = "Virga",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = summary,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 13.sp,
            ),
            modifier = GlanceModifier.fillMaxWidth(),
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        Button(
            text = backUpNowLabel,
            onClick = actionRunCallback<SyncAllActionCallback>(),
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
}

/** Triggers an immediate sync of all enabled tasks when the widget button is tapped. */
class SyncAllActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            VirgaWidgetEntryPoint::class.java,
        )
        val repo = entryPoint.syncTaskRepository()
        val scheduler = entryPoint.syncScheduler()

        val enabled = runCatching { repo.tasks.first().filter { it.enabled } }
            .getOrDefault(emptyList())
        enabled.forEach { scheduler.syncNow(it.id) }
    }
}

class SyncWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SyncWidget()
}
