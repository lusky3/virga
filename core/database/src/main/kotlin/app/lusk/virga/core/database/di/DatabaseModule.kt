package app.lusk.virga.core.database.di

import android.content.Context
import androidx.room.Room
import app.lusk.virga.core.database.VirgaDatabase
import app.lusk.virga.core.database.dao.AppStatsDao
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
            // Pre-production policy: destructive migration is acceptable in ALL build types
            // (release included), since there are no shipped users and wiping app data on a
            // schema change is fine. No Migration objects are registered yet, so without this
            // fallback a release build upgrading across a schema bump (e.g. v1→v2) would throw
            // at first DB access and crash on launch. Revisit with a real Migration once the
            // app has shipped users and data preservation matters.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides fun provideRemoteDao(db: VirgaDatabase): RemoteDao = db.remoteDao()
    @Provides fun provideSyncTaskDao(db: VirgaDatabase): SyncTaskDao = db.syncTaskDao()
    @Provides fun provideSyncRunDao(db: VirgaDatabase): SyncRunDao = db.syncRunDao()
    @Provides fun provideConflictDao(db: VirgaDatabase): ConflictDao = db.conflictDao()
    @Provides fun provideAppStatsDao(db: VirgaDatabase): AppStatsDao = db.appStatsDao()
}
