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
     * flavor BuildConfig fields. The redirect URI varies per provider because
     * Google enforces the reversed-client-id scheme on Android OAuth clients
     * (custom schemes like `virga://…` are rejected by both their Android
     * and Web client types).
     */
    @Provides
    @Singleton
    fun provideOAuthConfig(): OAuthConfig = OAuthConfig(
        defaultRedirectUri = REDIRECT_URI,
        clientIds = mapOf(
            OAuthProviders.GoogleDrive.id to BuildConfig.OAUTH_CLIENT_ID_GDRIVE,
            OAuthProviders.OneDrive.id to BuildConfig.OAUTH_CLIENT_ID_ONEDRIVE,
            OAuthProviders.Dropbox.id to BuildConfig.OAUTH_CLIENT_ID_DROPBOX,
        ),
        redirectUris = mapOf(
            OAuthProviders.GoogleDrive.id to googleAndroidRedirect(BuildConfig.OAUTH_CLIENT_ID_GDRIVE),
        ),
    )

    /** Must match the intent-filter on `OAuthRedirectActivity`. */
    const val REDIRECT_URI = "virga://oauth/callback"

    /**
     * Google's Android OAuth clients accept exactly one redirect URI, derived
     * from the client ID by reversing the host portion. For client
     * `123-abc.apps.googleusercontent.com` the URI is
     * `com.googleusercontent.apps.123-abc:/oauth2redirect`.
     */
    private fun googleAndroidRedirect(clientId: String): String {
        if (clientId.isBlank()) return REDIRECT_URI
        val core = clientId.removeSuffix(".apps.googleusercontent.com")
        return "com.googleusercontent.apps.$core:/oauth2redirect"
    }
}
