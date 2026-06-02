package app.lusk.virga.sync

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.lusk.virga.core.common.notification.NotificationChannelIds
import app.lusk.virga.core.common.notification.NotificationDeepLinks

/**
 * Optional persistent foreground "watchdog" service that keeps Virga's process
 * warm so WorkManager-scheduled syncs survive aggressive OEM background-killing
 * (Samsung One UI, Xiaomi MIUI, etc.). **Off by default**; toggled via
 * Settings → the `watchdogEnabled` preference → [WatchdogController].
 *
 * The ongoing notification is mandatory for any foreground service; it sits on a
 * MIN-importance channel so it stays collapsed. The actual schedule-repair work
 * (re-enqueueing dropped jobs) is done by [WatchdogController.onHeartbeat] via an
 * AlarmManager heartbeat — this service's only jobs are (a) to be a process the
 * OS is reluctant to kill and (b) to be restarted (START_STICKY) if it is.
 *
 * FGS type is `specialUse` on API 34+ (a periodic keep-alive babysitter fits no
 * other type); below 34 the 2-arg `startForeground` is used since `specialUse`
 * does not exist there.
 *
 * NOTE (flavor relocation): if Google Play rejects the `specialUse` declaration,
 * this file + its manifest `<service>` entry + the `FOREGROUND_SERVICE_SPECIAL_USE`
 * permission can be moved verbatim into an `app/src/foss/` source set without
 * touching any caller — [WatchdogController] already swallows start failures.
 */
class WatchdogService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // START_STICKY: if the OS kills us for memory, recreate the service so the
        // keep-alive resumes without the user reopening the app.
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // Pre-34 has no `specialUse` type; the 2-arg overload performs no
            // type-subset assertion, so this is safe on API 26–33.
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 44

        /** Starts the watchdog. Idempotent — a second call just re-delivers onStartCommand. */
        fun start(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stops the watchdog; onDestroy removes the ongoing notification. */
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }

        /**
         * Collapsed: title only. Expanded (via BigTextStyle): the description plus
         * "Settings" and "Turn off" actions. Tapping the body opens the app.
         */
        private fun buildNotification(context: Context): Notification {
            return NotificationCompat.Builder(context, NotificationChannelIds.WATCHDOG)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.watchdog_notif_title))
                // No setContentText → the collapsed notification shows just the title.
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.watchdog_notif_text)),
                )
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                // Let the system defer/soften the notification rather than flashing it.
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
                .setContentIntent(launchPendingIntent(context, openSettings = false))
                .addAction(
                    0,
                    context.getString(R.string.watchdog_action_settings),
                    launchPendingIntent(context, openSettings = true),
                )
                .addAction(
                    0,
                    context.getString(R.string.watchdog_action_turn_off),
                    disablePendingIntent(context),
                )
                .build()
        }

        /** Opens the app, optionally deep-linking to the Settings tab. */
        private fun launchPendingIntent(context: Context, openSettings: Boolean): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return null
            if (openSettings) {
                launch.putExtra(NotificationDeepLinks.EXTRA_OPEN_ROUTE, NotificationDeepLinks.ROUTE_SETTINGS)
                // Deliver the extra to the running instance via onNewIntent rather
                // than stacking a second activity.
                launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(
                context,
                if (openSettings) RC_SETTINGS else RC_LAUNCH,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** Broadcasts to [WatchdogReceiver] to disable the watchdog (flips the pref off). */
        private fun disablePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogReceiver::class.java).apply {
                action = WatchdogReceiver.ACTION_DISABLE
            }
            return PendingIntent.getBroadcast(
                context,
                RC_DISABLE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private const val RC_LAUNCH = 2
        private const val RC_SETTINGS = 3
        private const val RC_DISABLE = 4
    }
}
