package app.lusk.virga.core.rclone.oauth

/**
 * Per-provider OAuth client IDs and the redirect URI registered with each
 * provider. Provided by the app module from BuildConfig (so foss and play
 * flavors can ship different IDs, and the values stay out of library code).
 *
 * Empty values are placeholders — the OAuth flow code paths are exercised but
 * actual auth against the provider requires registered IDs at release time.
 */
data class OAuthConfig(
    val redirectUri: String,
    val clientIds: Map<String, String>,
) {
    fun clientId(providerId: String): String = clientIds[providerId].orEmpty()
}
