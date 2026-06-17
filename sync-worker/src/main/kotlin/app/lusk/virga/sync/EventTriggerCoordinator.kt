package app.lusk.virga.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns all event-driven sync trigger machinery for B3. Activated by
 * [WatchdogService] via [start]/[stop]; triggers are thus active exactly as
 * long as the watchdog FGS.
 *
 * Three trigger families:
 *  - **Folder change** ([FolderWatchSet], non-SAF paths only) — per-task debounce →
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
 * All mutable state is confined to a **single-threaded dispatcher** ([confinement]).
 * FileObserver/NetworkCallback/BroadcastReceiver callbacks dispatch onto it via
 * `scope.launch(confinement) { … }` before touching any shared state.
 *
 * `start()` and `stop()` are `@Synchronized` so scope creation and cancellation
 * are each atomic; a concurrent `stop()` cannot cancel a half-constructed scope.
 *
 * [scope] is `@Volatile` so the OS-callback threads that read it via the
 * dispatch* lambdas see the most recent write without requiring the class monitor.
 *
 * ## Teardown guarantee (P1 fix)
 *
 * Each watcher coroutine registers its resource on entry and unregisters it in a
 * `finally` block. When [stop] cancels the scope the CancellationException unwind
 * triggers every `finally`, so FileObservers, NetworkCallback, and BroadcastReceiver
 * are always unregistered regardless of cancellation timing.
 *
 * ## Selective rebuild (H2 fix)
 *
 * Each subsystem collects its own projected, `distinctUntilChanged` sub-flow so
 * an unrelated pref write or task-status update does not rebuild unaffected
 * observers/callbacks.
 *
 * Pure predicates ([shouldObserveTask], [isSafPath]) live at file scope so they
 * can be unit-tested without Android wiring.
 */
@Singleton
class EventTriggerCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val scheduler: SyncScheduler,
    private val taskRepository: SyncTaskRepository,
    private val preferencesRepository: PreferencesRepository,
    /**
     * Single-threaded dispatcher all mutable state accesses are confined to.
     * Production default: `Dispatchers.Default.limitedParallelism(1)`.
     * Tests: inject `UnconfinedTestDispatcher` so flows drain under `runTest`.
     */
    internal val confinement: kotlinx.coroutines.CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1),
) {

    // @GuardedBy("this") — only touched inside @Synchronized start()/stop().
    // @Volatile so OS-callback threads reading it via dispatch lambdas see fresh value.
    @Volatile
    private var scope: CoroutineScope? = null

    // Folder-watching subsystem; all state confined to [confinement].
    private val folderWatchSet = FolderWatchSet(
        scheduler = scheduler,
        scopeProvider = { scope },
        confinement = confinement,
    )

    /** Starts all trigger machinery. Idempotent — repeated calls while running are no-ops. */
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
     * Cancels the internal scope. Each watcher's `finally` block unregisters its
     * resource as CancellationException unwinds — no teardown race with launch.
     * Idempotent.
     */
    @Synchronized
    fun stop() {
        val s = scope ?: return
        scope = null
        s.cancel()
    }

    // -------------------------------------------------------------------------
    // Folder watching (H2: reacts only to folderChange flag + observable tasks)
    // -------------------------------------------------------------------------

    private suspend fun watchFolderTrigger() {
        val flagFlow = preferencesRepository.preferences
            .map { it.triggerOnFolderChange }
            .distinctUntilChanged()

        val tasksFlow = taskRepository.tasks
            .map { list -> list.filter(::shouldObserveTask).map { it.id to it.sourcePath } }
            .distinctUntilChanged()

        try {
            kotlinx.coroutines.flow.combine(flagFlow, tasksFlow) { flag, tasks -> flag to tasks }
                .collect { (flag, tasks) ->
                    folderWatchSet.tearDown()
                    if (flag) {
                        tasks.forEach { (id, path) -> folderWatchSet.watch(id, path) }
                    }
                }
        } finally {
            folderWatchSet.tearDown()
        }
    }

    // -------------------------------------------------------------------------
    // Wi-Fi connect trigger (H2: reacts only to triggerOnWifiConnect flag)
    // -------------------------------------------------------------------------

    private suspend fun watchWifiTrigger() {
        var networkCallback: ConnectivityManager.NetworkCallback? = null
        var debounceJob: Job? = null
        try {
            preferencesRepository.preferences
                .map { it.triggerOnWifiConnect }
                .distinctUntilChanged()
                .collect { enabled ->
                    networkCallback?.let { cb ->
                        runCatching {
                            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                                as ConnectivityManager
                            cm.unregisterNetworkCallback(cb)
                        }
                        networkCallback = null
                        debounceJob?.cancel()
                        debounceJob = null
                    }
                    if (enabled) {
                        networkCallback = registerWifiCallback(context) {
                            val s = scope ?: return@registerWifiCallback
                            s.launch(confinement) {
                                debounceJob?.cancel()
                                debounceJob = s.launch(confinement) {
                                    runCatching {
                                        delay(WIFI_DEBOUNCE_MS)
                                        scheduler.syncAllEnabled()
                                    }.onFailure { e ->
                                        if (e is CancellationException) throw e
                                        Log.w(TAG, "wifi-connect trigger failed", e)
                                    }
                                }
                            }
                        }
                    }
                }
        } finally {
            networkCallback?.let { cb ->
                runCatching {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                        as ConnectivityManager
                    cm.unregisterNetworkCallback(cb)
                }
            }
            debounceJob?.cancel()
        }
    }

    // -------------------------------------------------------------------------
    // Charging connect trigger (H2: reacts only to triggerOnCharge flag)
    // -------------------------------------------------------------------------

    private suspend fun watchChargeTrigger() {
        var chargeReceiver: BroadcastReceiver? = null
        var debounceJob: Job? = null
        try {
            preferencesRepository.preferences
                .map { it.triggerOnCharge }
                .distinctUntilChanged()
                .collect { enabled ->
                    chargeReceiver?.let { r ->
                        runCatching { context.unregisterReceiver(r) }
                        chargeReceiver = null
                        debounceJob?.cancel()
                        debounceJob = null
                    }
                    if (enabled) {
                        chargeReceiver = registerChargeReceiver(context) {
                            val s = scope ?: return@registerChargeReceiver
                            s.launch(confinement) {
                                debounceJob?.cancel()
                                debounceJob = s.launch(confinement) {
                                    runCatching {
                                        delay(CHARGE_DEBOUNCE_MS)
                                        scheduler.syncAllEnabled()
                                    }.onFailure { e ->
                                        if (e is CancellationException) throw e
                                        Log.w(TAG, "charge-connect trigger failed", e)
                                    }
                                }
                            }
                        }
                    }
                }
        } finally {
            chargeReceiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
            debounceJob?.cancel()
        }
    }

    companion object {
        private const val TAG = "EventTriggerCoordinator"

        /** Quiet window after Wi-Fi becomes available before triggering (10 s). */
        const val WIFI_DEBOUNCE_MS = 10_000L

        /** Quiet window after charger connects before triggering (10 s). */
        const val CHARGE_DEBOUNCE_MS = 10_000L
    }
}

// -------------------------------------------------------------------------
// Top-level helpers — pure or context-only, no class state
// -------------------------------------------------------------------------

/**
 * Registers a [ConnectivityManager.NetworkCallback] for WIFI+INTERNET and returns it.
 * The caller is responsible for unregistering. Swallows registration failures (best-effort).
 */
private fun registerWifiCallback(
    context: Context,
    onAvailable: () -> Unit,
): ConnectivityManager.NetworkCallback? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
    }
    return runCatching { cm.registerNetworkCallback(request, callback); callback }.getOrNull()
}

/**
 * Registers a [BroadcastReceiver] for [Intent.ACTION_POWER_CONNECTED] and returns it.
 * The caller is responsible for unregistering. Swallows registration failures (best-effort).
 */
private fun registerChargeReceiver(context: Context, onConnected: () -> Unit): BroadcastReceiver? {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_CONNECTED) onConnected()
        }
    }
    return runCatching {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        receiver
    }.getOrNull()
}

// -------------------------------------------------------------------------
// Pure predicates — tested independently, no Android wiring
// -------------------------------------------------------------------------

/** Returns true when a FileObserver should be registered for [task]. */
fun shouldObserveTask(task: SyncTask): Boolean = task.enabled && !isSafPath(task.sourcePath)

/** Returns true when [path] is a SAF/DocumentProvider content URI. */
fun isSafPath(path: String): Boolean = path.startsWith("content://")
