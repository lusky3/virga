package app.lusk.virga.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.lusk.virga.core.database.dao.RemoteDao
import app.lusk.virga.core.database.dao.SyncRunDao
import app.lusk.virga.core.database.dao.SyncTaskDao
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity

@Database(
    entities = [RemoteEntity::class, SyncTaskEntity::class, SyncRunEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class VirgaDatabase : RoomDatabase() {
    abstract fun remoteDao(): RemoteDao
    abstract fun syncTaskDao(): SyncTaskDao
    abstract fun syncRunDao(): SyncRunDao

    companion object {
        const val NAME = "virga.db"
    }
}
