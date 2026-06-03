package app.lusk.virga.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlayUpdateModule {
    @Binds
    @Singleton
    abstract fun bind(impl: PlayUpdateChecker): UpdateChecker
}
