package app.lusk.virga.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.lusk.virga.core.database.dao.ConflictDao
import app.lusk.virga.core.database.dao.RemoteDao
import app.lusk.virga.core.database.dao.SyncRunDao
import app.lusk.virga.core.database.dao.SyncTaskDao
import app.lusk.virga.core.database.entity.ConflictEntity
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity

@Database(
    entities = [
        RemoteEntity::class,
        SyncTaskEntity::class,
        SyncRunEntity::class,
        ConflictEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class VirgaDatabase : RoomDatabase() {
    abstract fun remoteDao(): RemoteDao
    abstract fun syncTaskDao(): SyncTaskDao
    abstract fun syncRunDao(): SyncRunDao
    abstract fun conflictDao(): ConflictDao

    companion object {
        const val NAME = "virga.db"

        /**
         * v2 → v3: add the calendar-schedule columns to sync_tasks (day-of-week
         * bitmask + time-of-day). Additive only — existing rows default to 0/9/0
         * (no calendar schedule), preserving all data.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE sync_tasks ADD COLUMN scheduleDaysMask INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE sync_tasks ADD COLUMN scheduleHour INTEGER NOT NULL DEFAULT 9")
                connection.execSQL("ALTER TABLE sync_tasks ADD COLUMN scheduleMinute INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
