package app.lusk.virga.core.rclone.oauth

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OAuth 2.0 + PKCE flow helpers for building authorization URLs and exchanging
 * authorization codes for tokens, returning a JSON string in the shape rclone
 * stores in its config (`{"access_token":"…","refresh_token":"…","expiry":"…"}`).
 */
@Singleton
class OAuthTokenExchanger @Inject constructor(
    private val httpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class PendingAuth(
        val provider: OAuthProvider,
        val state: String,
        val verifier: String,
        val clientId: String,
        val redirectUri: String,
        /**
         * Remote name the user typed before the round-trip. Carried in the
         * (singleton-stored) pending auth so it survives ViewModel/process
         * recreation while the browser is open.
         */
        val remoteName: String = "",
    )

    /** Builds the full authorize URL the user is sent to in Custom Tabs. */
    fun authorizeUrl(p: PendingAuth): String {
        val base = p.provider.authEndpoint
        val params = mutableMapOf(
            "client_id" to p.clientId,
            "redirect_uri" to p.redirectUri,
            "response_type" to "code",
            "scope" to p.provider.scopes.joinToString(" "),
            "state" to p.state,
            "code_challenge" to Pkce.challenge(p.verifier),
            "code_challenge_method" to "S256",
        )
        if (p.provider.id == OAuthProviders.Dropbox.id) {
            // Dropbox needs this flag to issue a refresh token.
            params["token_access_type"] = "offline"
        }
        return base + "?" + params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    /** Exchanges [code] for tokens and returns the rclone-shaped token JSON. */
    suspend fun exchange(p: PendingAuth, code: String): Result<String> = withContext(dispatchers.io) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", p.redirectUri)
            .add("client_id", p.clientId)
            .add("code_verifier", p.verifier)
            .build()
        val request = Request.Builder()
            .url(p.provider.tokenEndpoint)
            .post(body)
            .header("Accept", "application/json")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        VirgaError.Auth(
                            remote = p.provider.id,
                            message = "Token exchange failed (${response.code}): ${text.take(200)}",
                        ),
                    )
                }
                val parsed = json.parseToJsonElement(text) as? JsonObject
                    ?: return@withContext Result.failure(
                        VirgaError.Auth(p.provider.id, "Token endpoint returned non-JSON"),
                    )
                Result.success(rcloneToken(parsed))
            }
        } catch (e: IOException) {
            Result.failure(VirgaError.Network("Token exchange network failure", e))
        } catch (e: SerializationException) {
            // parseToJsonElement throws on malformed JSON; keep it inside Result.
            Result.failure(VirgaError.Auth(p.provider.id, "Token endpoint returned malformed JSON", e))
        }
    }

    /**
     * Provider-specific extra rclone config derived from the freshly-issued
     * token. OneDrive's backend requires `drive_id` + `drive_type` (rclone
     * normally resolves these during interactive config); we fetch them from
     * Microsoft Graph so `config/create` can run non-interactively. Other
     * providers need no extras.
     */
    suspend fun providerConfigExtras(
        provider: OAuthProvider,
        rcloneTokenJson: String,
    ): Result<Map<String, String>> {
        if (provider.id != OAuthProviders.OneDrive.id) return Result.success(emptyMap())
        val accessToken = runCatching {
            (json.parseToJsonElement(rcloneTokenJson) as JsonObject)["access_token"]?.jsonPrimitive?.content
        }.getOrNull()
        if (accessToken.isNullOrBlank()) {
            return Result.failure(VirgaError.Auth(provider.id, "Could not read OneDrive access token"))
        }
        return fetchOneDriveDrive(accessToken).map { (id, type) ->
            mapOf("drive_id" to id, "drive_type" to type)
        }
    }

    /** Fetches the signed-in user's OneDrive drive id + type from Microsoft Graph. */
    private suspend fun fetchOneDriveDrive(accessToken: String): Result<Pair<String, String>> =
        withContext(dispatchers.io) {
            val request = Request.Builder()
                .url("https://graph.microsoft.com/v1.0/me/drive")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .get()
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            VirgaError.Auth("onedrive", "Graph /me/drive failed (${response.code}): ${text.take(200)}"),
                        )
                    }
                    val obj = json.parseToJsonElement(text) as? JsonObject
                        ?: return@withContext Result.failure(
                            VirgaError.Auth("onedrive", "Graph /me/drive returned non-JSON"),
                        )
                    val id = obj["id"]?.jsonPrimitive?.content
                        ?: return@withContext Result.failure(
                            VirgaError.Auth("onedrive", "Graph /me/drive missing drive id"),
                        )
                    val driveType = obj["driveType"]?.jsonPrimitive?.content ?: "personal"
                    Result.success(id to driveType)
                }
            } catch (e: IOException) {
                Result.failure(VirgaError.Network("Graph /me/drive network failure", e))
            } catch (e: SerializationException) {
                Result.failure(VirgaError.Auth("onedrive", "Graph /me/drive returned malformed JSON", e))
            }
        }

    /**
     * Builds the JSON rclone expects in `[remote].token`. rclone writes its
     * `expiry` as an RFC 3339 instant; we compute one from `expires_in`.
     */
    private fun rcloneToken(tokenResponse: JsonObject): String {
        val expiresIn = tokenResponse["expires_in"]?.jsonPrimitive?.intOrNull ?: 0
        val expiry = OffsetDateTime.now(ZoneOffset.UTC)
            .plusSeconds(expiresIn.toLong())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val rclone = buildJsonObject {
            tokenResponse["access_token"]?.let { put("access_token", it) }
            put("token_type", tokenResponse["token_type"] ?: kotlinx.serialization.json.JsonPrimitive("Bearer"))
            tokenResponse["refresh_token"]?.let { put("refresh_token", it) }
            put("expiry", expiry)
        }
        return json.encodeToString(JsonObject.serializer(), rclone)
    }
}
