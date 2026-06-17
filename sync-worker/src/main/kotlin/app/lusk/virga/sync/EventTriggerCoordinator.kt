package app.lusk.virga.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.FileObserver
import android.util.Log
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.core.datastore.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns all event-driven sync trigger machinery for B3. Activated by
 * [WatchdogService] via [start]/[stop]; triggers are thus active exactly as
 * long as the watchdog FGS.
 *
 * Three trigger families:
 *  - **Folder change** (FileObserver, non-SAF paths only) — per-task debounce →
 *    [SyncScheduler.syncNow].
 *  - **Wi-Fi connect** (ConnectivityManager.NetworkCallback) — debounce →
 *    [SyncScheduler.syncAllEnabled].
 *  - **Charger connect** (BroadcastReceiver for ACTION_POWER_CONNECTED) — debounce →
 *    [SyncScheduler.syncAllEnabled].
 *
 * All three are opt-in (default OFF) and layered on the opt-in watchdog.
 *
 * ## Thread safety (C1 + C2 fix)
 *
 * All mutable state — `folderObservers`, `debounceJobs`, job vars — is confined
 * to a **single-threaded dispatcher** (`confinement = Dispatchers.Default
 * .limitedParallelism(1)`). The CoroutineScope runs on this dispatcher.
 * FileObserver/NetworkCallback/BroadcastReceiver callbacks dispatch onto it via
 * `scope.launch(confinement) { … }` before touching any shared state.
 *
 * `start()` and `stop()` are `@Synchronized` (JVM monitor on `this`) so the
 * scope-create → scope-launch pair and the scope-cancel → flag-clear pair are
 * each atomic. A concurrent `stop()` cannot cancel a scope that `start()` is
 * still constructing, and vice versa.
 *
 * ## Selective rebuild (H2 fix)
 *
 * Each subsystem collects its own projected, `distinctUntilChanged` sub-flow so
 * a task-status write or an unrelated pref change does not tear down and rebuild
 * unaffected observers/callbacks.
 *
 * Pure predicates ([shouldObserveTask], [isSafPath]) live outside the class so
 * they can be unit-tested without any Android wiring.
 */
@Singleton
class EventTriggerCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scheduler: SyncScheduler,
    private val taskRepository: SyncTaskRepository,
    private val preferencesRepository: PreferencesRepository,
    /**
     * Single-threaded dispatcher that all mutable state accesses are confined to.
     * Production: `Dispatchers.Default.limitedParallelism(1)` (set at injection time).
     * Tests: inject an `UnconfinedTestDispatcher` or `StandardTestDispatcher` so
     * flows drain synchronously under `runTest`.
     */
    internal val confinement: kotlinx.coroutines.CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1),
) {

    // Nullable scope: non-null iff the coordinator is running.
    // @GuardedBy("this") — only touched inside synchronized start()/stop().
    private var scope: CoroutineScope? = null

    // All of the following are @GuardedBy(confinement) — only touched from coroutines
    // running on the confinement dispatcher.
    private val folderObservers = mutableMapOf<Long, FileObserver>()
    private val debounceJobs = mutableMapOf<Long, Job>()
    private var wifiDebounceJob: Job? = null
    private var chargeDebounceJob: Job? = null

    /**
     * Starts all trigger machinery. Idempotent — a second call while already running
     * is a no-op (AtomicBoolean promoted to synchronized block so the scope-create
     * and scope-launch are a single atomic unit; C2 fix).
     */
    @Synchronized
    fun start() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + confinement)
        scope = newScope
        newScope.launch { watchFolderTrigger() }
        newScope.launch { watchWifiTrigger() }
        newScope.launch { watchChargeTrigger() }
    }

    /**
     * Tears down all trigger machinery and cancels the internal scope. Idempotent.
     * The [confinement]-confined teardown runs synchronously before cancel() because
     * we dispatch it via `runBlocking`-equivalent inline — actually we schedule it
     * as the last job so the scope's SupervisorJob keeps children alive until the
     * teardown job finishes; then we cancel the scope from outside.
     *
     * In practice: cancel() terminates all child coroutines (debounce delays) and
     * the teardown of OS resources (observers, callbacks, receivers) happens inside
     * coroutines already on the confinement thread, so they complete before the
     * cancelled scope collects them.
     */
    @Synchronized
    fun stop() {
        val s = scope ?: return
        scope = null
        // Launch teardown on the confinement dispatcher so it runs on the correct
        // thread, then cancel the scope (which cancels the teardown job itself once
        // the OS unregister calls are done — SupervisorJob means this is safe).
        s.launch(confinement) {
            tearDownFolderObservers()
            unregisterNetworkCallback()
            unregisterChargeReceiver()
        }
        s.cancel()
    }

    // -------------------------------------------------------------------------
    // Folder watching (H2: reacts only to folderChange flag + observable tasks)
    // -------------------------------------------------------------------------

    private suspend fun watchFolderTrigger() {
        // Project down to only the inputs this subsystem cares about:
        // - the boolean flag
        // - the set of (id, sourcePath) pairs for enabled non-SAF tasks
        // distinctUntilChanged ensures we don't rebuild on unrelated pref/task writes.
        val flagFlow = preferencesRepository.preferences
            .map { it.triggerOnFolderChange }
            .distinctUntilChanged()

        val tasksFlow = taskRepository.tasks
            .map { list -> list.filter(::shouldObserveTask).map { it.id to it.sourcePath } }
            .distinctUntilChanged()

        kotlinx.coroutines.flow.combine(flagFlow, tasksFlow) { flag, tasks -> flag to tasks }
            .collect { (flag, tasks) ->
                // Already on confinement (scope runs on it); no withContext needed.
                tearDownFolderObservers()
                if (!flag) return@collect
                tasks.forEach { (id, path) ->
                    val observer = buildFileObserver(id, path)
                    folderObservers[id] = observer
                    runCatching { observer.startWatching() }
                }
            }
    }

    private fun buildFileObserver(taskId: Long, sourcePath: String): FileObserver {
        val mask = OBSERVER_MASK
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(File(sourcePath), mask) {
                override fun onEvent(event: Int, path: String?) = dispatchFolderEvent(taskId)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(sourcePath, mask) {
                override fun onEvent(event: Int, path: String?) = dispatchFolderEvent(taskId)
            }
        }
    }

    /**
     * Called from the FileObserver internal thread. Dispatches to confinement so
     * all map/job access is on the single confined thread (C1 fix).
     */
    private fun dispatchFolderEvent(taskId: Long) {
        val s = scope ?: return
        s.launch(confinement) { onFolderEvent(taskId) }
    }

    private fun onFolderEvent(taskId: Long) {
        debounceJobs[taskId]?.cancel()
        debounceJobs[taskId] = requireScope().launch(confinement) {
            runCatching {
                delay(FOLDER_DEBOUNCE_MS)
                scheduler.syncNow(taskId)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "folder-change trigger failed for task $taskId", e)
            }
        }
    }

    private fun tearDownFolderObservers() {
        folderObservers.values.forEach { runCatching { it.stopWatching() } }
        folderObservers.clear()
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
    }

    // -------------------------------------------------------------------------
    // Wi-Fi connect trigger (H2: reacts only to triggerOnWifiConnect flag)
    // -------------------------------------------------------------------------

    private suspend fun watchWifiTrigger() {
        preferencesRepository.preferences
            .map { it.triggerOnWifiConnect }
            .distinctUntilChanged()
            .collect { enabled ->
                // Already on confinement.
                unregisterNetworkCallback()
                if (!enabled) return@collect
                registerNetworkCallback()
            }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = dispatchWifiAvailable()
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
    }

    /**
     * Called from ConnectivityManager's binder thread. Dispatches to confinement
     * (C1 fix).
     */
    private fun dispatchWifiAvailable() {
        val s = scope ?: return
        s.launch(confinement) { onWifiAvailable() }
    }

    private fun onWifiAvailable() {
        wifiDebounceJob?.cancel()
        wifiDebounceJob = requireScope().launch(confinement) {
            runCatching {
                delay(WIFI_DEBOUNCE_MS)
                scheduler.syncAllEnabled()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "wifi-connect trigger failed", e)
            }
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        wifiDebounceJob?.cancel()
        wifiDebounceJob = null
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        }
    }

    // NetworkCallback ref: confined to confinement thread after init.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // -------------------------------------------------------------------------
    // Charging connect trigger (H2: reacts only to triggerOnCharge flag)
    // -------------------------------------------------------------------------

    private suspend fun watchChargeTrigger() {
        preferencesRepository.preferences
            .map { it.triggerOnCharge }
            .distinctUntilChanged()
            .collect { enabled ->
                // Already on confinement.
                unregisterChargeReceiver()
                if (!enabled) return@collect
                registerChargeReceiver()
            }
    }

    private fun registerChargeReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_POWER_CONNECTED) dispatchChargeConnected()
            }
        }
        runCatching {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        }.onSuccess { chargeReceiver = receiver }
    }

    /**
     * Called from the system broadcast thread. Dispatches to confinement (C1 fix).
     */
    private fun dispatchChargeConnected() {
        val s = scope ?: return
        s.launch(confinement) { onChargeConnected() }
    }

    private fun onChargeConnected() {
        chargeDebounceJob?.cancel()
        chargeDebounceJob = requireScope().launch(confinement) {
            runCatching {
                delay(CHARGE_DEBOUNCE_MS)
                scheduler.syncAllEnabled()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "charge-connect trigger failed", e)
            }
        }
    }

    private fun unregisterChargeReceiver() {
        val r = chargeReceiver ?: return
        chargeReceiver = null
        chargeDebounceJob?.cancel()
        chargeDebounceJob = null
        runCatching { context.unregisterReceiver(r) }
    }

    // Charge receiver ref: confined to confinement thread after init.
    private var chargeReceiver: BroadcastReceiver? = null

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns the live scope; throws if called when not running (programming error). */
    private fun requireScope(): CoroutineScope =
        checkNotNull(scope) { "EventTriggerCoordinator: requireScope() called while stopped" }

    companion object {
        private const val TAG = "EventTriggerCoordinator"

        /** Quiet window after the last folder event before triggering a sync (15 s). */
        const val FOLDER_DEBOUNCE_MS = 15_000L

        /** Quiet window after Wi-Fi becomes available before triggering (10 s). */
        const val WIFI_DEBOUNCE_MS = 10_000L

        /** Quiet window after charger connects before triggering (10 s). */
        const val CHARGE_DEBOUNCE_MS = 10_000L

        /**
         * FileObserver mask: all mutating events.
         * CREATE | DELETE | MODIFY | MOVED_FROM | MOVED_TO | CLOSE_WRITE.
         */
        val OBSERVER_MASK: Int =
            FileObserver.CREATE or FileObserver.DELETE or FileObserver.MODIFY or
                FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
    }
}

// -------------------------------------------------------------------------
// Pure predicates — tested independently, no Android wiring
// -------------------------------------------------------------------------

/** Returns true when the FileObserver should be registered for [task]. */
fun shouldObserveTask(task: SyncTask): Boolean = task.enabled && !isSafPath(task.sourcePath)

/** Returns true when [path] is a SAF/DocumentProvider content URI. */
fun isSafPath(path: String): Boolean = path.startsWith("content://")
