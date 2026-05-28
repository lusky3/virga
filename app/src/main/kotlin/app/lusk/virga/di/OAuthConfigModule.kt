package app.lusk.virga.di

import app.lusk.virga.BuildConfig
import app.lusk.virga.core.rclone.oauth.OAuthConfig
import app.lusk.virga.core.rclone.oauth.OAuthProviders
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OAuthConfigModule {

    /**
     * Builds the [OAuthConfig] the OAuth flow uses. Client IDs come from per-
     * flavor BuildConfig fields; the redirect URI is the single custom scheme
     * registered with the redirect activity in feature:remotes.
     */
    @Provides
    @Singleton
    fun provideOAuthConfig(): OAuthConfig = OAuthConfig(
        redirectUri = REDIRECT_URI,
        clientIds = mapOf(
            OAuthProviders.GoogleDrive.id to BuildConfig.OAUTH_CLIENT_ID_GDRIVE,
            OAuthProviders.OneDrive.id to BuildConfig.OAUTH_CLIENT_ID_ONEDRIVE,
            OAuthProviders.Dropbox.id to BuildConfig.OAUTH_CLIENT_ID_DROPBOX,
        ),
    )

    /** Must match the intent-filter on `OAuthRedirectActivity`. */
    const val REDIRECT_URI = "virga://oauth/callback"
}
