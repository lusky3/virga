package app.lusk.virga.core.database.di

import android.content.Context
import androidx.room.Room
import app.lusk.virga.core.database.VirgaDatabase
import app.lusk.virga.core.database.dao.ConflictDao
import app.lusk.virga.core.database.dao.RemoteDao
import app.lusk.virga.core.database.dao.SyncRunDao
import app.lusk.virga.core.database.dao.SyncTaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VirgaDatabase =
        Room.databaseBuilder(context, VirgaDatabase::class.java, VirgaDatabase.NAME)
            // Pre-release: schema is still evolving. Drop and recreate on
            // version mismatch instead of writing per-version migrations.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides fun provideRemoteDao(db: VirgaDatabase): RemoteDao = db.remoteDao()
    @Provides fun provideSyncTaskDao(db: VirgaDatabase): SyncTaskDao = db.syncTaskDao()
    @Provides fun provideSyncRunDao(db: VirgaDatabase): SyncRunDao = db.syncRunDao()
    @Provides fun provideConflictDao(db: VirgaDatabase): ConflictDao = db.conflictDao()
}
