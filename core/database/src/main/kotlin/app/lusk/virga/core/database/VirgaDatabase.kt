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
    version = 5,
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

        /**
         * v3 -> v4: add the per-task Mirror flag (deleteExtraneous). Additive;
         * existing rows default to 0 (additive copy, never deletes).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE sync_tasks ADD COLUMN deleteExtraneous INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 → v5: WS3.1 Tier-2 rclone options.
         *   checksum  — compare by hash; default false (0).
         *   backupDir — nullable rclone BackupDir path; existing rows = null.
         *   maxDelete — nullable MaxDelete abort threshold; existing rows = null.
         *   extraConfig — newline-separated Key=Value passthrough; default "".
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE sync_tasks ADD COLUMN checksum INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE sync_tasks ADD COLUMN backupDir TEXT")
                connection.execSQL("ALTER TABLE sync_tasks ADD COLUMN maxDelete INTEGER")
                connection.execSQL("ALTER TABLE sync_tasks ADD COLUMN extraConfig TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
