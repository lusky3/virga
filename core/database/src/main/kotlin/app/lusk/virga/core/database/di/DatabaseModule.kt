package app.lusk.virga.core.database.di

import android.content.Context
import androidx.room.Room
import app.lusk.virga.core.database.BuildConfig
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
            // No migrations yet: the schema is a single pre-release v1 baseline (see
            // VirgaDatabase). The debug-only destructive fallback wipes data on any local
            // schema edit during development. A RELEASE build registers no migrations, so
            // the first post-release schema change (v1→v2) MUST add a real Migration here
            // or Room will throw at startup — the intended guard against silent data loss.
            .apply {
                if (BuildConfig.DEBUG) {
                    fallbackToDestructiveMigration(dropAllTables = true)
                    fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                }
            }
            .build()

    @Provides fun provideRemoteDao(db: VirgaDatabase): RemoteDao = db.remoteDao()
    @Provides fun provideSyncTaskDao(db: VirgaDatabase): SyncTaskDao = db.syncTaskDao()
    @Provides fun provideSyncRunDao(db: VirgaDatabase): SyncRunDao = db.syncRunDao()
    @Provides fun provideConflictDao(db: VirgaDatabase): ConflictDao = db.conflictDao()
    @Provides fun provideAppStatsDao(db: VirgaDatabase): AppStatsDao = db.appStatsDao()
}
