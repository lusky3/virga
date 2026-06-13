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
     * flavor BuildConfig fields. The redirect URI varies per provider:
     *  - OneDrive / Dropbox use a verified HTTPS App Link ([REDIRECT_URI]) so no
     *    other installed app can claim the redirect (custom-scheme hijack defense).
     *    Requires the assetlinks.json at https://lusk.app/.well-known/ to be live
     *    and the URI registered in each provider's console.
     *  - Google enforces the reversed-client-id scheme on its Android OAuth client
     *    type (HTTPS redirects require a Web client + secret), so it keeps that
     *    scheme — which is already unique per client ID, not a hijack risk.
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
            OAuthProviders.GoogleDrive.id to
                OAuthConfig.googleAndroidRedirect(BuildConfig.OAUTH_CLIENT_ID_GDRIVE, REDIRECT_URI),
        ),
    )

    /**
     * Verified HTTPS App Link. Must match the autoVerify intent-filter on
     * `OAuthRedirectActivity` and the hosted assetlinks.json host.
     */
    const val REDIRECT_URI = "https://lusk.app/virga/oauth/callback"
}
