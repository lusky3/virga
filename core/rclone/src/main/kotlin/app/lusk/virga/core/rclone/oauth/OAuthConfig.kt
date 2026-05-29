package app.lusk.virga.core.rclone.oauth

/**
 * Per-provider OAuth client IDs and redirect URIs. Supplied by the app module
 * (so foss / play flavors and per-developer client IDs stay out of library
 * code).
 *
 * Different providers enforce different redirect URI rules:
 *
 *  - **Google Drive (Android client)** requires a reverse-domain URI derived
 *    from the client ID:
 *    `com.googleusercontent.apps.<reversed-client-id>:/oauth2redirect`.
 *    Google's "Web application" client type rejects custom schemes
 *    (`virga://…`) outright because they have no public TLD.
 *  - **Microsoft / Dropbox / pCloud** accept any custom scheme registered on
 *    the device, so we use `virga://oauth/callback` for them.
 *
 * Use [redirectUri] to fetch the right one for the provider being launched.
 */
data class OAuthConfig(
    val defaultRedirectUri: String,
    val clientIds: Map<String, String>,
    /** Per-provider override; if absent, [defaultRedirectUri] is used. */
    val redirectUris: Map<String, String> = emptyMap(),
) {
    fun clientId(providerId: String): String = clientIds[providerId].orEmpty()
    fun redirectUri(providerId: String): String =
        redirectUris[providerId] ?: defaultRedirectUri
}
