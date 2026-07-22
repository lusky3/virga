package app.lusk.virga.di

import app.lusk.virga.BuildConfig
import app.lusk.virga.sync.WatchdogAvailable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Supplies [WatchdogAvailable] from the per-flavor `WATCHDOG_AVAILABLE` BuildConfig
 * field so `:sync-worker` (flavor-agnostic) can gate the watchdog without depending
 * on `:app`. See `app/build.gradle.kts`'s `distribution()` for the per-flavor value.
 */
@Module
@InstallIn(SingletonComponent::class)
object WatchdogAvailabilityModule {
    @Provides
    @WatchdogAvailable
    fun provideWatchdogAvailable(): Boolean = BuildConfig.WATCHDOG_AVAILABLE
}
