package app.lusk.virga.sync

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.notification.NotificationChannelIds

/** Builds the notifications shown during and after a sync. */
internal class SyncNotifications(private val context: Context) {

    fun progress(taskName: String, progress: SyncProgress?): Notification =
        NotificationCompat.Builder(context, NotificationChannelIds.SYNC_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Syncing $taskName")
            .setContentText(progress?.let(::progressText) ?: "Starting…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (progress != null && progress.totalBytes > 0) {
                    setProgress(PROGRESS_MAX, (progress.fraction * PROGRESS_MAX).toInt(), false)
                } else {
                    setProgress(PROGRESS_MAX, 0, true)
                }
            }
            .build()

    fun complete(taskName: String, filesTransferred: Int): Notification =
        NotificationCompat.Builder(context, NotificationChannelIds.SYNC_COMPLETE)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("$taskName synced")
            .setContentText("$filesTransferred file(s) transferred")
            .setAutoCancel(true)
            .build()

    fun error(taskName: String, message: String): Notification =
        NotificationCompat.Builder(context, NotificationChannelIds.SYNC_ERROR)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$taskName failed")
            .setContentText(message)
            .setAutoCancel(true)
            .build()

    private fun progressText(p: SyncProgress): String {
        val pct = (p.fraction * 100).toInt()
        val speed = (p.speedBytesPerSec / 1_000_000).format1() + " MB/s"
        return "$pct% • ${p.transferredFiles}/${p.totalFiles} files • $speed"
    }

    private fun Double.format1(): String = "%.1f".format(this)

    companion object {
        const val PROGRESS_MAX = 100
        const val FOREGROUND_NOTIFICATION_ID = 42
        const val RESULT_NOTIFICATION_ID = 43
    }
}
