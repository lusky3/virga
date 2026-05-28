package app.lusk.virga.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import app.lusk.virga.core.common.notification.NotificationChannelIds.SYNC_COMPLETE
import app.lusk.virga.core.common.notification.NotificationChannelIds.SYNC_ERROR
import app.lusk.virga.core.common.notification.NotificationChannelIds.SYNC_PROGRESS

/**
 * Notification channels. Required since minSdk 26. Registered once at app
 * startup; re-registering with the same id is a no-op so this is idempotent.
 */
object NotificationChannels {

    fun register(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    SYNC_PROGRESS,
                    "Sync progress",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Ongoing sync notifications" },
                NotificationChannel(
                    SYNC_COMPLETE,
                    "Sync complete",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "A sync finished or failed" },
                NotificationChannel(
                    SYNC_ERROR,
                    "Sync errors",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Authentication, permission, or storage problems" },
            ),
        )
    }
}
