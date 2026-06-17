package app.lusk.virga.widget

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.lusk.virga.R
import app.lusk.virga.sync.TAG_SYNC_ALL
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Quick Settings tile that backs up all enabled sync tasks immediately.
 * Android instantiates TileService outside the normal Hilt graph, so we
 * use [VirgaWidgetEntryPoint] to reach the application-scoped singletons.
 */
class SyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        // L7: read enabled tasks and enqueue their syncs on a process-lifetime scope,
        // NOT a service-local one. A QS tile's service is torn down the moment the
        // shade collapses; a service-scoped coroutine would be cancelled before the
        // suspending tasks.first() read completes, dropping the work. WorkManager
        // (via SyncScheduler.syncNow) owns the durable sync once enqueued — this
        // detached scope only needs to live long enough to read + enqueue.
        val appContext = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext,
                VirgaWidgetEntryPoint::class.java,
            )
            val scheduler = entryPoint.syncScheduler()
            runCatching { scheduler.syncAllEnabled() }
        }
    }

    private fun refreshTileState() {
        val appContext = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // WorkManager.getInstance is the same pattern used by SyncScheduler itself;
            // no Hilt binding exists for WorkManager so we call it directly here.
            val infos = runCatching {
                // getWorkInfosByTag returns a ListenableFuture; .get() is a blocking
                // call, safe here because this coroutine already runs on Dispatchers.IO.
                // Bounded so a stalled WorkManager can't hang this coroutine and leave
                // the tile state stale forever — a timeout falls back to "not syncing".
                WorkManager.getInstance(appContext)
                    .getWorkInfosByTag(TAG_SYNC_ALL)
                    .get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.onFailure {
                Log.w(TAG, "WorkInfo query for tile state failed or timed out", it)
            }.getOrDefault(emptyList())
            val syncing = isSyncActive(infos)
            // qsTile is a UI object owned by the main thread; switch back before touching it.
            withContext(Dispatchers.Main) { updateTileState(syncing) }
        }
    }

    private fun updateTileState(syncing: Boolean) {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (syncing) R.string.tile_subtitle_syncing else R.string.tile_subtitle,
            )
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_sync)
        tile.updateTile()
    }

    private companion object {
        const val TAG = "SyncTileService"

        /** Upper bound on the blocking WorkInfo query so a stalled WorkManager can't hang us. */
        const val WORK_QUERY_TIMEOUT_SECONDS = 2L
    }
}

/**
 * Returns true when any of [infos] represents in-progress or queued sync work.
 * Pure + top-level `internal` (like [app.lusk.virga.share.disambiguate]) so it can be
 * unit-tested directly without a live WorkManager or the TileService framework lifecycle.
 */
internal fun isSyncActive(infos: List<WorkInfo>): Boolean =
    infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
