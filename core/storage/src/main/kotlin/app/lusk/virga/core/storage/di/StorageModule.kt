package app.lusk.virga.core.storage.di

import app.lusk.virga.core.storage.StorageAccessor
import app.lusk.virga.core.storage.StorageAccessorImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface StorageModule {
    @Binds
    @Singleton
    fun bindStorageAccessor(impl: StorageAccessorImpl): StorageAccessor
}
