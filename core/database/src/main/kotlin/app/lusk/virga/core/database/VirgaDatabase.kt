package app.lusk.virga.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
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

@Database(
    entities = [
        RemoteEntity::class,
        SyncTaskEntity::class,
        SyncRunEntity::class,
        ConflictEntity::class,
        AppStatsEntity::class,
    ],
    version = 6,
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

        /**
         * v1 → v2: add the `conflicts` table (bisync conflict tracking). Additive;
         * no existing data is touched. DDL matches schemas/2.json verbatim.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conflicts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`taskId` INTEGER NOT NULL, `remoteName` TEXT NOT NULL, " +
                        "`basePath` TEXT NOT NULL, `variant1Path` TEXT NOT NULL, " +
                        "`variant2Path` TEXT NOT NULL, `variant1Size` INTEGER NOT NULL, " +
                        "`variant2Size` INTEGER NOT NULL, `detectedAtEpochMs` INTEGER NOT NULL, " +
                        "`resolved` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`taskId`) REFERENCES `sync_tasks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_conflicts_taskId` ON `conflicts` (`taskId`)")
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_conflicts_remoteName_basePath` " +
                        "ON `conflicts` (`remoteName`, `basePath`)",
                )
            }
        }

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

        /**
         * v5 → v6: introduce the `app_stats` singleton-row table for lifetime
         * sync statistics. The row is seeded immediately so DAOs never see a
         * missing row on fresh-install paths that skip the ensureRow() call.
         *
         * Column types match Room's code-gen for [AppStatsEntity]:
         *   - Long (non-null) → INTEGER NOT NULL DEFAULT 0
         *   - Int  (non-null) → INTEGER NOT NULL DEFAULT 0
         *   - Long? (nullable) → INTEGER (no NOT NULL, no DEFAULT — Room stores null)
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_stats` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`firstSyncEpochMs` INTEGER, " +
                        "`totalRuns` INTEGER NOT NULL DEFAULT 0, " +
                        "`successfulRuns` INTEGER NOT NULL DEFAULT 0, " +
                        "`failedRuns` INTEGER NOT NULL DEFAULT 0, " +
                        "`totalFilesTransferred` INTEGER NOT NULL DEFAULT 0, " +
                        "`totalBytesTransferred` INTEGER NOT NULL DEFAULT 0, " +
                        "`bytesUploaded` INTEGER NOT NULL DEFAULT 0, " +
                        "`bytesDownloaded` INTEGER NOT NULL DEFAULT 0, " +
                        "`bytesTwoWay` INTEGER NOT NULL DEFAULT 0, " +
                        "`totalSyncMillis` INTEGER NOT NULL DEFAULT 0, " +
                        "`largestRunBytes` INTEGER NOT NULL DEFAULT 0, " +
                        "`longestRunMillis` INTEGER NOT NULL DEFAULT 0, " +
                        "`currentStreakDays` INTEGER NOT NULL DEFAULT 0, " +
                        "`longestStreakDays` INTEGER NOT NULL DEFAULT 0, " +
                        "`lastSyncDayEpochDay` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))",
                )
                connection.execSQL("INSERT OR IGNORE INTO `app_stats` (`id`) VALUES (0)")
            }
        }
    }
}
