package app.lusk.virga

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.notification.NotificationChannels
import app.lusk.virga.sync.WatchdogController
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VirgaApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncHistory: SyncHistoryRepository
    @Inject lateinit var preferences: PreferencesRepository
    @Inject lateinit var watchdog: WatchdogController

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.register(this)
        // Reconcile runs left RUNNING by a worker killed mid-sync (process death
        // can't run the worker's finally), so history doesn't show a phantom
        // in-progress sync after a crash/force-stop.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        appScope.launch {
            runCatching { syncHistory.reconcileInterruptedRuns() }
        }
        // Keep the persistent watchdog in sync with its preference. Runs for the
        // app's lifetime: applies the saved state on every launch and reacts to
        // the Settings toggle live. Start failures are swallowed by the controller
        // (background FGS starts can be blocked); BootReceiver covers the reboot path.
        appScope.launch {
            preferences.preferences
                .map { it.watchdogEnabled }
                .distinctUntilChanged()
                .collect { enabled -> watchdog.setEnabled(enabled) }
        }
    }
}
