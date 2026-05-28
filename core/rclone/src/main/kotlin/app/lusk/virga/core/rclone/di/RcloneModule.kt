package app.lusk.virga.core.rclone.di

import app.lusk.virga.core.common.dispatchers.DefaultDispatcherProvider
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.rclone.RcloneEngine
import app.lusk.virga.core.rclone.RcloneEngineImpl
import app.lusk.virga.core.rclone.api.RcApiClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RcloneProvidesModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // RC calls are localhost; keep timeouts generous for slow daemon startup
        // but bounded so a hung daemon surfaces as an error.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRcApiClient(client: OkHttpClient): RcApiClient = RcApiClient(client)

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()
}

@Module
@InstallIn(SingletonComponent::class)
interface RcloneBindsModule {
    @Binds
    @Singleton
    fun bindRcloneEngine(impl: RcloneEngineImpl): RcloneEngine
}
