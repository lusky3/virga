package app.lusk.virga

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.lusk.virga.core.data.SyncHistoryRepository
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.rclone.RcloneEngine
import app.lusk.virga.notification.NotificationChannels
import app.lusk.virga.sync.WatchdogController
import app.lusk.virga.telemetry.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VirgaApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncHistory: SyncHistoryRepository
    @Inject lateinit var preferences: PreferencesRepository
    @Inject lateinit var watchdog: WatchdogController
    @Inject lateinit var rcloneEngine: RcloneEngine
    @Inject lateinit var crashReporter: CrashReporter

    // Captured at Application construction (before any worker can run) so reconcile
    // only fails runs that were already in flight before this process started.
    private val processStartMs = System.currentTimeMillis()

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
        // The per-app locale is restored synchronously before onCreate by AppCompat's
        // AppLocalesMetadataHolderService (autoStoreLocales=true in the manifest), so there
        // is no startup work to do here — applying it async would race first render.
        appScope.launch {
            runCatching { syncHistory.reconcileInterruptedRuns(processStartMs) }
                .onFailure { Log.w(TAG, "Failed to reconcile interrupted runs on startup", it) }
            // A worker killed mid-sync (process death) can't run its finally, so the
            // decrypted plaintext rclone config (OAuth tokens) it had open may still be
            // on disk. Purge it via the engine, which is lease-aware: it no-ops if a
            // worker that raced this startup already holds the daemon, so it can never
            // delete a config in active use. The next daemon start re-decrypts anyway.
            runCatching { rcloneEngine.cleanupStaleConfigIfIdle() }
                .onFailure { Log.w(TAG, "Failed to clean stale decrypted config on startup", it) }
            val retentionDays = runCatching {
                preferences.preferences.first().runRetentionDays
            }.getOrDefault(0)
            if (retentionDays > 0) {
                val cutoffMs = System.currentTimeMillis() - retentionDays * 86_400_000L
                runCatching { syncHistory.pruneOlderThan(cutoffMs) }
                    .onFailure { Log.w(TAG, "Failed to prune history on startup", it) }
            }
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
        // Opt-in crash reporting: initialize/close Sentry to follow the saved toggle.
        // Default OFF, so the FOSS build makes no telemetry call unless the user opts in
        // (and only when a DSN was configured at build time — CrashReporter no-ops otherwise).
        appScope.launch {
            preferences.preferences
                .map { it.crashReportingEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    runCatching { crashReporter.setEnabled(enabled) }
                        .onFailure { Log.w(TAG, "Failed to apply crash-reporting toggle", it) }
                }
        }
    }

    private companion object {
        const val TAG = "VirgaApplication"
    }
}
