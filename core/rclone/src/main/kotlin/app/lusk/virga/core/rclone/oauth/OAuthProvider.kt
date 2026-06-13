package app.lusk.virga.core.rclone.oauth

/**
 * Configuration for an OAuth provider Virga supports out of the box. [type] is
 * the corresponding rclone backend name used when calling `config/create`.
 *
 * These providers all support PKCE for public clients, so no client secret has
 * to ship in the APK.
 */
data class OAuthProvider(
    val id: String,
    val displayName: String,
    /** rclone backend type, e.g. "drive", "onedrive", "dropbox". */
    val type: String,
    val authEndpoint: String,
    val tokenEndpoint: String,
    val scopes: List<String>,
)

object OAuthProviders {

    val GoogleDrive = OAuthProvider(
        id = "gdrive",
        displayName = "Google Drive",
        type = "drive",
        authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenEndpoint = "https://oauth2.googleapis.com/token",
        scopes = listOf("https://www.googleapis.com/auth/drive"),
    )

    val OneDrive = OAuthProvider(
        id = "onedrive",
        displayName = "OneDrive",
        type = "onedrive",
        authEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        scopes = listOf("Files.ReadWrite.All", "offline_access"),
    )

    val Dropbox = OAuthProvider(
        id = "dropbox",
        displayName = "Dropbox",
        type = "dropbox",
        authEndpoint = "https://www.dropbox.com/oauth2/authorize",
        tokenEndpoint = "https://api.dropboxapi.com/oauth2/token",
        // Dropbox requires `token_access_type=offline` (set on auth URL, not a scope) for refresh.
        scopes = listOf("files.content.write", "files.content.read", "files.metadata.read"),
    )

    val All = listOf(GoogleDrive, OneDrive, Dropbox)

    fun byId(id: String): OAuthProvider? = All.firstOrNull { it.id == id }
}
