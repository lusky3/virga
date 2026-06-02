package app.lusk.virga.sync

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.notification.NotificationChannelIds

/** Builds the notifications shown during and after a sync. */
internal class SyncNotifications(private val context: Context) {

    /**
     * In-progress notification for [taskName]. Includes a Cancel action and a
     * tap-to-open content intent.
     *
     * TODO: deep-link the contentIntent to the specific task/run screen once
     *   the Navigation-3 custom navigator exposes a stable deep-link URI for it.
     *   For now we just open the app launch activity.
     */
    fun progress(taskName: String, progress: SyncProgress?, taskId: Long): Notification =
        NotificationCompat.Builder(context, NotificationChannelIds.SYNC_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.notif_sync_progress_title, taskName))
            .setContentText(progress?.let(::progressText) ?: context.getString(R.string.notif_sync_starting))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent())
            .apply {
                if (progress != null && progress.totalBytes > 0) {
                    setProgress(PROGRESS_MAX, (progress.fraction * PROGRESS_MAX).toInt(), false)
                } else {
                    setProgress(PROGRESS_MAX, 0, true)
                }
                if (taskId > 0) {
                    addAction(
                        android.R.drawable.ic_delete,
                        context.getString(R.string.notif_action_cancel),
                        actionIntent(SyncActionReceiver.ACTION_CANCEL, taskId, RC_CANCEL),
                    )
                }
            }
            .build()

    /**
     * Completion notification for [taskName]. Tapping opens the app.
     *
     * TODO: deep-link to the run detail screen once Navigation-3 supports it.
     */
    fun complete(taskName: String, filesTransferred: Int, taskId: Long): Notification =
        NotificationCompat.Builder(context, NotificationChannelIds.SYNC_COMPLETE)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(context.getString(R.string.notif_sync_complete_title, taskName))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.notif_files_transferred, filesTransferred, filesTransferred,
                ),
            )
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()

    /**
     * Error notification for [taskName]. Includes a Retry action and tapping
     * opens the app.
     *
     * TODO: deep-link to the run detail screen once Navigation-3 supports it.
     */
    fun error(taskName: String, message: String, taskId: Long): Notification =
        NotificationCompat.Builder(context, NotificationChannelIds.SYNC_ERROR)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.notif_sync_error_title, taskName))
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .apply {
                if (taskId > 0) {
                    addAction(
                        android.R.drawable.ic_menu_rotate,
                        context.getString(R.string.notif_action_retry),
                        actionIntent(SyncActionReceiver.ACTION_RETRY, taskId, RC_RETRY),
                    )
                }
            }
            .build()

    /** Returns a PendingIntent that opens the app's launch activity. Null-safe. */
    private fun launchIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            RC_LAUNCH,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Builds an explicit broadcast PendingIntent targeting [SyncActionReceiver]. */
    private fun actionIntent(action: String, taskId: Long, actionOffset: Int): PendingIntent {
        val intent = Intent(action).apply {
            setClass(context, SyncActionReceiver::class.java)
            putExtra(SyncActionReceiver.EXTRA_TASK_ID, taskId)
        }
        // Interleave the task id and the action bit so Cancel/Retry request codes
        // never collide across tasks (the old fixed 1000-wide bands could alias,
        // e.g. Cancel of task 1000 == Retry of task 0). Only aliases past ~1.07B
        // task ids, which Room autoincrement won't reach.
        val requestCode = (taskId.toInt() shl 1) + actionOffset
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun progressText(p: SyncProgress): String {
        val pct = (p.fraction * 100).toInt()
        val speed = (p.speedBytesPerSec / 1_000_000).format1() + " MB/s"
        return context.getString(R.string.notif_sync_progress, pct, p.transferredFiles, p.totalFiles, speed)
    }

    private fun Double.format1(): String = "%.1f".format(this)

    companion object {
        const val PROGRESS_MAX = 100
        const val FOREGROUND_NOTIFICATION_ID = 42
        const val RESULT_NOTIFICATION_ID = 43

        // Request-code bases — offset by taskId to avoid per-task collisions.
        private const val RC_LAUNCH      = 0
        private const val RC_CANCEL = 0
        private const val RC_RETRY = 1
    }
}
