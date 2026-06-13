package app.lusk.virga.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lusk.virga.core.datastore.PreferencesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Re-registers periodic sync schedules after the device reboots, and also after
 * a timezone/clock change (so calendar schedules — baked into a fixed delay at
 * enqueue time — don't drift relative to the new wall-clock time).
 *
 * Dependencies are resolved through [EntryPointAccessors] rather than
 * `@AndroidEntryPoint` because the OS may deliver `LOCKED_BOOT_COMPLETED`
 * before the Hilt component exists (notably during instrumented tests, where
 * the component is created by `HiltAndroidRule.inject()` and not on app
 * start). The lookup is guarded with `runCatching` so the receiver is a no-op
 * in that window instead of crashing the process.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun scheduler(): SyncScheduler
        fun preferences(): PreferencesRepository
        fun watchdog(): WatchdogController
    }

    override fun onReceive(context: Context, intent: Intent) {
        // BOOT/LOCKED_BOOT re-arm schedules after a reboot. TIMEZONE_CHANGED and
        // TIME_SET (ACTION_TIME_CHANGED) re-arm them after a clock/zone change:
        // calendar schedules are baked into a fixed ms delay at enqueue time, so
        // without this they drift (up to a week for a weekly schedule) when the
        // wall-clock time shifts. rescheduleAll() recomputes the next occurrence.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_TIMEZONE_CHANGED &&
            intent.action != Intent.ACTION_TIME_CHANGED
        ) {
            return
        }
        val entryPoint = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                BootReceiverEntryPoint::class.java,
            )
        }.getOrNull() ?: return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Bound the work to the goAsync() window (~10s) so a hung
                // rescheduleAll() can't blow past it; reschedule retries next boot.
                withTimeout(8_000) {
                    entryPoint.scheduler().rescheduleAll()
                    // Re-arm the persistent watchdog if the user enabled it. BOOT_COMPLETED
                    // grants the background foreground-service-start exemption, so this is
                    // the reliable place to start it after a reboot.
                    if (entryPoint.preferences().preferences.first().watchdogEnabled) {
                        entryPoint.watchdog().setEnabled(true)
                    }
                }
            } catch (_: Exception) {
                // Timed out or failed — safe to ignore; rescheduled on next boot/launch.
            } finally {
                pending.finish()
            }
        }
    }
}
