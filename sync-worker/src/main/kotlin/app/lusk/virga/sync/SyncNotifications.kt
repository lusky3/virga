package app.lusk.virga.sync

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.notification.NotificationChannelIds
import app.lusk.virga.core.common.notification.NotificationDeepLinks

/** Builds the notifications shown during and after a sync. */
internal class SyncNotifications(private val context: Context) {

    /**
     * In-progress notification for [taskName]. Includes a Cancel action and a
     * tap-to-open content intent that deep-links to the task's summary screen.
     */
    fun progress(taskName: String, progress: SyncProgress?, taskId: Long): Notification =
        NotificationCompat.Builder(context, NotificationChannelIds.SYNC_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_virga)
            .setContentTitle(context.getString(R.string.notif_sync_progress_title, taskName))
            .setContentText(progress?.let(::progressText) ?: context.getString(R.string.notif_sync_starting))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(taskContentIntent(taskId))
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

    /** Completion notification for [taskName]. Tapping opens the task's summary. */
    fun complete(taskName: String, filesTransferred: Int, taskId: Long): Notification =
        NotificationCompat.Builder(context, NotificationChannelIds.SYNC_COMPLETE)
            .setSmallIcon(R.drawable.ic_stat_virga)
            .setContentTitle(context.getString(R.string.notif_sync_complete_title, taskName))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.notif_files_transferred, filesTransferred, filesTransferred,
                ),
            )
            .setAutoCancel(true)
            .setContentIntent(taskContentIntent(taskId))
            .build()

    /** Error notification for [taskName]. Includes a Retry action; tapping opens the task's summary. */
    fun error(taskName: String, message: String, taskId: Long): Notification =
        NotificationCompat.Builder(context, NotificationChannelIds.SYNC_ERROR)
            .setSmallIcon(R.drawable.ic_stat_virga)
            .setContentTitle(context.getString(R.string.notif_sync_error_title, taskName))
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(taskContentIntent(taskId))
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

    /**
     * PendingIntent that opens the app and deep-links to [taskId]'s summary screen
     * (via the launch activity + [NotificationDeepLinks] extras, which MainActivity
     * reads). Falls back to a plain launch when [taskId] isn't a real id. Null-safe.
     */
    private fun taskContentIntent(taskId: Long): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        if (taskId > 0) {
            launch.putExtra(NotificationDeepLinks.EXTRA_OPEN_ROUTE, NotificationDeepLinks.ROUTE_TASK)
            launch.putExtra(NotificationDeepLinks.EXTRA_TASK_ID, taskId)
            // Deliver to the running instance (onNewIntent) instead of stacking one.
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        // Distinct request code per task so concurrent notifications don't share
        // (and overwrite) each other's extras under FLAG_UPDATE_CURRENT.
        val requestCode = if (taskId > 0) RC_CONTENT_BASE + taskId.toInt() else RC_LAUNCH
        return PendingIntent.getActivity(
            context,
            requestCode,
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

        // Per-task notification IDs: concurrent syncs ("Sync all") must each get
        // their own progress + result notification, otherwise they clobber each
        // other (the Cancel action would target only the last-posted one, and N
        // failures would collapse into a single notification). Bases are far apart
        // so a foreground id can never alias a result id for plausible task ids
        // (collision needs taskId difference >= 100000, which Room won't reach).
        private const val FOREGROUND_BASE = 100_000
        private const val RESULT_BASE = 200_000

        /** Foreground (progress) notification id for [taskId]. */
        fun foregroundId(taskId: Long): Int = FOREGROUND_BASE + taskId.toInt()

        /** Result (success/error) notification id for [taskId]. */
        fun resultId(taskId: Long): Int = RESULT_BASE + taskId.toInt()

        // Request-code bases — offset by taskId to avoid per-task collisions.
        private const val RC_LAUNCH = 0
        private const val RC_CANCEL = 0
        private const val RC_RETRY = 1
        // Content-intent request codes get their own high base (offset by taskId) so
        // they never collide with the launch/cancel/retry codes above.
        private const val RC_CONTENT_BASE = 1_000_000
    }
}
