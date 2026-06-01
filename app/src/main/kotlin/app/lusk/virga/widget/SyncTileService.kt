package app.lusk.virga.widget

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.lusk.virga.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that backs up all enabled sync tasks immediately.
 * Android instantiates TileService outside the normal Hilt graph, so we
 * use [VirgaWidgetEntryPoint] to reach the application-scoped singletons.
 */
class SyncTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val entryPoint = entryPoint()
            val repo = entryPoint.syncTaskRepository()
            val scheduler = entryPoint.syncScheduler()
            val enabled = runCatching { repo.tasks.first().filter { it.enabled } }
                .getOrDefault(emptyList())
            enabled.forEach { scheduler.syncNow(it.id) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.tile_subtitle)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_sync)
        tile.updateTile()
    }

    private fun entryPoint(): VirgaWidgetEntryPoint =
        EntryPointAccessors.fromApplication(
            applicationContext,
            VirgaWidgetEntryPoint::class.java,
        )
}
