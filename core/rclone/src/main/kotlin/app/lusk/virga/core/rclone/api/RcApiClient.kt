package app.lusk.virga.core.rclone.api

import app.lusk.virga.core.common.error.VirgaError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Thin client for the rclone remote-control HTTP+JSON API. Every endpoint is a
 * POST to `/<command>` with a JSON body; auth is HTTP Basic with the per-session
 * credentials the daemon was started with.
 *
 * See https://rclone.org/rc/ for the command catalogue.
 */
class RcApiClient(
    private val httpClient: OkHttpClient,
    private val json: Json = DefaultJson,
) {
    /**
     * Calls [command] (e.g. "config/listremotes") with [params] and returns the
     * parsed JSON object response. Throws [VirgaError] on transport/HTTP errors.
     */
    suspend fun call(
        baseUrl: String,
        user: String,
        pass: String,
        command: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = withContext(Dispatchers.IO) {
        val body = json.encodeToString(JsonObject.serializer(), params)
            .toRequestBody(null)
        val request = Request.Builder()
            .url("$baseUrl/$command")
            .header("Authorization", Credentials.basic(user, pass))
            // rclone's rcserver does an exact string compare against
            // "application/json" — adding a charset suffix makes it skip
            // body parsing and treat every input parameter as missing. We
            // attach the header explicitly so OkHttp can't append the
            // charset for us.
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw VirgaError.Rclone(
                        exitCode = response.code,
                        message = rcErrorMessage(text, response.code),
                    )
                }
                if (text.isBlank()) JsonObject(emptyMap())
                else json.parseToJsonElement(text).asObjectOrEmpty()
            }
        } catch (e: IOException) {
            throw VirgaError.Network("Could not reach rclone daemon: ${e.message}", e)
        }
    }

    private fun rcErrorMessage(body: String, code: Int): String =
        runCatching {
            (json.parseToJsonElement(body) as? JsonObject)
                ?.get("error")?.toString()?.trim('"')
        }.getOrNull() ?: "rclone RC error (HTTP $code)"

    private fun JsonElement.asObjectOrEmpty(): JsonObject =
        this as? JsonObject ?: JsonObject(emptyMap())

    companion object {
        // rclone's rcserver does a strict `contentType == "application/json"`
        // comparison (no media-type parser), so we must NOT add `;charset=utf-8`.
        // With a charset suffix the daemon silently ignores the JSON body and
        // returns "Didn't find key X in input" for every parameter.
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}
