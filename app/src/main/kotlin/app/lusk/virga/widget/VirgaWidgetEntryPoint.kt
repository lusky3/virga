package app.lusk.virga.widget

import app.lusk.virga.core.data.SyncTaskRepository
import app.lusk.virga.sync.SyncScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for widget and tile components that Android instantiates
 * outside the normal injected graph (GlanceAppWidget, TileService).
 * Access via [EntryPointAccessors.fromApplication].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VirgaWidgetEntryPoint {
    fun syncTaskRepository(): SyncTaskRepository
    fun syncScheduler(): SyncScheduler
}
