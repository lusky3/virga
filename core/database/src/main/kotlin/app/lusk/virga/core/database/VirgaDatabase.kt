package app.lusk.virga.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.lusk.virga.core.database.dao.AppStatsDao
import app.lusk.virga.core.database.dao.ConflictDao
import app.lusk.virga.core.database.dao.RemoteDao
import app.lusk.virga.core.database.dao.SyncRunDao
import app.lusk.virga.core.database.dao.SyncTaskDao
import app.lusk.virga.core.database.entity.AppStatsEntity
import app.lusk.virga.core.database.entity.ConflictEntity
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity

/**
 * Schema is at version 1: the app is pre-release, so the incremental v1→v7 migration
 * history (added while iterating) was collapsed into a single v1 baseline rather than
 * carried forward. The first published build ships as v1; real [androidx.room.migration.Migration]s
 * are added from v2 onward once there are installs with data to preserve. Until then,
 * DatabaseModule's debug-only destructive fallback handles any local schema edits.
 */
@Database(
    entities = [
        RemoteEntity::class,
        SyncTaskEntity::class,
        SyncRunEntity::class,
        ConflictEntity::class,
        AppStatsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class VirgaDatabase : RoomDatabase() {
    abstract fun remoteDao(): RemoteDao
    abstract fun syncTaskDao(): SyncTaskDao
    abstract fun syncRunDao(): SyncRunDao
    abstract fun conflictDao(): ConflictDao
    abstract fun appStatsDao(): AppStatsDao

    companion object {
        const val NAME = "virga.db"
    }
}
