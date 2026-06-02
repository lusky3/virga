package app.lusk.virga.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform side-effects the watchdog performs, abstracted behind an interface
 * so [WatchdogController]'s enable/disable/heartbeat *decisions* are unit-testable
 * without Android (Context, AlarmManager, Service).
 *
 * Implementations swallow their own failures (e.g. a background
 * `ForegroundServiceStartNotAllowedException` on API 31+); the controller treats
 * every call as best-effort and relies on the heartbeat / START_STICKY for retry.
 */
interface WatchdogPlatform {
    fun startService()
    fun stopService()
    fun scheduleHeartbeat()
    fun cancelHeartbeat()
    suspend fun rescheduleSyncs()
}

/** Real implementation backed by [WatchdogService], [AlarmManager] and [SyncScheduler]. */
@Singleton
class RealWatchdogPlatform @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scheduler: SyncScheduler,
) : WatchdogPlatform {

    private val alarmManager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun startService() {
        runCatching { WatchdogService.start(context) }
    }

    override fun stopService() {
        runCatching { WatchdogService.stop(context) }
    }

    override suspend fun rescheduleSyncs() {
        scheduler.rescheduleAll()
    }

    override fun scheduleHeartbeat() {
        val triggerAt = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS
        // Inexact + allow-while-idle fires even in Doze and needs no restricted
        // SCHEDULE_EXACT_ALARM permission; precision is irrelevant for a watchdog.
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, heartbeatIntent())
        }
    }

    override fun cancelHeartbeat() {
        runCatching { alarmManager.cancel(heartbeatIntent()) }
    }

    private fun heartbeatIntent(): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).apply {
            action = WatchdogReceiver.ACTION_HEARTBEAT
        }
        return PendingIntent.getBroadcast(
            context,
            RC_HEARTBEAT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        val HEARTBEAT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15)
        const val RC_HEARTBEAT = 7001
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WatchdogModule {
    @Binds
    abstract fun bindWatchdogPlatform(impl: RealWatchdogPlatform): WatchdogPlatform
}
