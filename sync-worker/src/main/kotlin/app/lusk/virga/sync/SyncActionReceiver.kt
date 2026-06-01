package app.lusk.virga.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles notification actions (Cancel / Retry) for in-progress and failed syncs.
 *
 * Triggered only by explicit PendingIntents built in [SyncNotifications]; the
 * receiver is not exported and carries no intent-filter, so it is unreachable
 * from outside the app.
 */
@AndroidEntryPoint
class SyncActionReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: SyncScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId <= 0L) return

        when (intent.action) {
            ACTION_CANCEL -> scheduler.cancel(taskId)
            ACTION_RETRY  -> scheduler.syncNow(taskId)
            else          -> return
        }

        // Dismiss the result (error) notification after acting on it.
        NotificationManagerCompat.from(context).cancel(SyncNotifications.RESULT_NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_CANCEL  = "app.lusk.virga.action.CANCEL_SYNC"
        const val ACTION_RETRY   = "app.lusk.virga.action.RETRY_SYNC"
        const val EXTRA_TASK_ID  = "task_id"
    }
}
