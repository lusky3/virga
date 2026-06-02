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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Receives the watchdog heartbeat alarm scheduled by [WatchdogController] and
 * hands it back to the controller to repair the schedule + re-arm.
 *
 * Dependencies are resolved via [EntryPointAccessors] (not `@AndroidEntryPoint`)
 * to mirror [BootReceiver]: the alarm can fire very early in process startup, and
 * the lookup is guarded so the receiver is a no-op if the Hilt component isn't
 * ready yet rather than crashing.
 */
class WatchdogReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WatchdogReceiverEntryPoint {
        fun controller(): WatchdogController
        fun preferences(): PreferencesRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HEARTBEAT && intent.action != ACTION_DISABLE) return
        val entryPoint = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WatchdogReceiverEntryPoint::class.java,
            )
        }.getOrNull() ?: return

        val action = intent.action
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Bound to the goAsync() window (~10s) so a hung call can't overrun it.
                withTimeout(8_000) {
                    when (action) {
                        ACTION_HEARTBEAT -> entryPoint.controller().onHeartbeat()
                        ACTION_DISABLE -> {
                            // Flip the persisted toggle off (so Settings reflects it and
                            // it stays off across restarts), then tear down immediately —
                            // the app-side preference observer also reacts, but doing it
                            // here guarantees the service stops even if no UI is running.
                            entryPoint.preferences().setWatchdogEnabled(false)
                            entryPoint.controller().setEnabled(false)
                        }
                    }
                }
            } catch (_: Exception) {
                // Timed out or failed — heartbeat re-arms next tick; disable is retried
                // by the preference observer.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_HEARTBEAT = "app.lusk.virga.watchdog.HEARTBEAT"
        const val ACTION_DISABLE = "app.lusk.virga.watchdog.DISABLE"
    }
}
