package app.lusk.virga.sync

import android.os.Build
import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manages the set of [FileObserver]s and per-task debounce jobs for the
 * folder-change trigger. Extracted from [EventTriggerCoordinator] to stay
 * within detekt's TooManyFunctions limit.
 *
 * All mutable state ([observers], [debounceJobs]) is accessed only from
 * coroutines running on [confinement] — same single-threaded dispatcher as
 * the coordinator — so no additional synchronisation is needed here.
 *
 * @param scheduler Sync scheduler to call when a debounce window expires.
 * @param scopeProvider Returns the coordinator's live [CoroutineScope], or
 *   null when the coordinator is stopped. Used when launching debounce jobs.
 * @param confinement The single-threaded dispatcher all accesses run on.
 */
internal class FolderWatchSet(
    private val scheduler: SyncScheduler,
    private val scopeProvider: () -> CoroutineScope?,
    private val confinement: CoroutineDispatcher,
) {
    // @GuardedBy(confinement)
    private val observers = mutableMapOf<Long, FileObserver>()
    private val debounceJobs = mutableMapOf<Long, Job>()

    /**
     * Registers a [FileObserver] for [sourcePath] and maps it to [taskId].
     * Swallows start failures (best-effort).
     */
    fun watch(taskId: Long, sourcePath: String) {
        val observer = buildObserver(taskId, sourcePath)
        observers[taskId] = observer
        runCatching { observer.startWatching() }
    }

    /** Stops all observers and cancels all pending debounce jobs. */
    fun tearDown() {
        observers.values.forEach { runCatching { it.stopWatching() } }
        observers.clear()
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
    }

    /**
     * Called from the FileObserver internal thread. Dispatches to [confinement]
     * before touching [debounceJobs] (C1 fix).
     */
    fun dispatchEvent(taskId: Long) {
        val s = scopeProvider() ?: return
        s.launch(confinement) { onEvent(taskId) }
    }

    private fun onEvent(taskId: Long) {
        debounceJobs[taskId]?.cancel()
        val s = scopeProvider() ?: return
        debounceJobs[taskId] = s.launch(confinement) {
            runCatching {
                delay(FOLDER_DEBOUNCE_MS)
                scheduler.syncNow(taskId)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "folder-change trigger failed for task $taskId", e)
            }
        }
    }

    private fun buildObserver(taskId: Long, sourcePath: String): FileObserver =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(File(sourcePath), OBSERVER_MASK) {
                override fun onEvent(event: Int, path: String?) = dispatchEvent(taskId)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(sourcePath, OBSERVER_MASK) {
                override fun onEvent(event: Int, path: String?) = dispatchEvent(taskId)
            }
        }

    /** Exposes [observers] keys for test assertions (same package). */
    internal fun observerIds(): Set<Long> = observers.keys.toSet()

    companion object {
        private const val TAG = "FolderWatchSet"

        /** Quiet window after the last folder event before triggering a sync (15 s). */
        const val FOLDER_DEBOUNCE_MS = 15_000L

        /**
         * FileObserver mask: all mutating events.
         * CREATE | DELETE | MODIFY | MOVED_FROM | MOVED_TO | CLOSE_WRITE.
         */
        val OBSERVER_MASK: Int =
            FileObserver.CREATE or FileObserver.DELETE or FileObserver.MODIFY or
                FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
    }
}
