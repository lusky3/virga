package app.lusk.virga

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VirgaApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncHistory: SyncHistoryRepository

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
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { syncHistory.reconcileInterruptedRuns() }
        }
    }
}
