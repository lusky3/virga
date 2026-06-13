package app.lusk.virga.core.rclone.oauth

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OAuthTokenExchangerTest {

    private lateinit var server: MockWebServer
    private lateinit var exchanger: OAuthTokenExchanger

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        exchanger = OAuthTokenExchanger(OkHttpClient(), TestDispatchers)
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun provider(id: String = "gdrive", type: String = "drive") = OAuthProvider(
        id = id,
        displayName = id,
        type = type,
        authEndpoint = "https://example.test/authorize",
        tokenEndpoint = server.url("/token").toString(),
        scopes = listOf("scope.a", "scope.b"),
    )

    private fun pending(p: OAuthProvider = provider()) = OAuthTokenExchanger.PendingAuth(
        provider = p,
        state = "state-xyz",
        verifier = "verifier-abc",
        clientId = "client-123",
        redirectUri = "virga://oauth/callback",
    )

    // --- authorizeUrl ----------------------------------------------------------

    @Test
    fun authorizeUrl_includesPkceAndAllRequiredParameters() {
        val url = exchanger.authorizeUrl(pending()).toHttpUrl()

        assertThat(url.queryParameter("client_id")).isEqualTo("client-123")
        assertThat(url.queryParameter("redirect_uri")).isEqualTo("virga://oauth/callback")
        assertThat(url.queryParameter("response_type")).isEqualTo("code")
        assertThat(url.queryParameter("scope")).isEqualTo("scope.a scope.b")
        assertThat(url.queryParameter("state")).isEqualTo("state-xyz")
        assertThat(url.queryParameter("code_challenge_method")).isEqualTo("S256")
        assertThat(url.queryParameter("code_challenge")).isEqualTo(Pkce.challenge("verifier-abc"))
    }

    @Test
    fun authorizeUrl_dropboxAddsOfflineFlag() {
        val url = exchanger.authorizeUrl(pending(OAuthProviders.Dropbox)).toHttpUrl()

        assertThat(url.queryParameter("token_access_type")).isEqualTo("offline")
    }

    @Test
    fun authorizeUrl_nonDropboxOmitsOfflineFlag() {
        val url = exchanger.authorizeUrl(pending(OAuthProviders.GoogleDrive)).toHttpUrl()

        assertThat(url.queryParameter("token_access_type")).isNull()
    }

    // --- exchange ---------------------------------------------------------------

    @Test
    fun exchange_postsPkceFormBody() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"a","token_type":"Bearer","refresh_token":"r","expires_in":3600}""",
            ),
        )

        exchanger.exchange(pending(), code = "auth-code-1").getOrThrow()

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8().split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            k to java.net.URLDecoder.decode(v, "UTF-8")
        }
        assertThat(body).containsEntry("grant_type", "authorization_code")
        assertThat(body).containsEntry("code", "auth-code-1")
        assertThat(body).containsEntry("redirect_uri", "virga://oauth/callback")
        assertThat(body).containsEntry("client_id", "client-123")
        assertThat(body).containsEntry("code_verifier", "verifier-abc")
    }

    @Test
    fun exchange_returnsRcloneShapedTokenJson() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"abc","token_type":"Bearer","refresh_token":"r1","expires_in":3600}""",
            ),
        )

        val result = exchanger.exchange(pending(), code = "ok").getOrThrow()
        val parsed = Json.parseToJsonElement(result) as JsonObject

        assertThat(parsed["access_token"]?.jsonPrimitive?.content).isEqualTo("abc")
        assertThat(parsed["token_type"]?.jsonPrimitive?.content).isEqualTo("Bearer")
        assertThat(parsed["refresh_token"]?.jsonPrimitive?.content).isEqualTo("r1")
        // expiry is RFC 3339 (ISO_OFFSET_DATE_TIME); just verify it parses + is roughly 1h ahead.
        val expiry = java.time.OffsetDateTime.parse(parsed["expiry"]!!.jsonPrimitive.content)
        val nowPlusHour = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(3600)
        val deltaSeconds = java.time.Duration.between(expiry, nowPlusHour).abs().seconds
        assertThat(deltaSeconds).isLessThan(60L)
    }

    @Test
    fun exchange_defaultsTokenTypeToBearer_whenMissing() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"abc","expires_in":60}""",
            ),
        )

        val result = exchanger.exchange(pending(), code = "ok").getOrThrow()
        val parsed = Json.parseToJsonElement(result) as JsonObject

        assertThat(parsed["token_type"]?.jsonPrimitive?.content).isEqualTo("Bearer")
    }

    @Test
    fun exchange_maps4xxToAuthError() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""),
        )

        val result = exchanger.exchange(pending(), code = "bad")

        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(VirgaError.Auth::class.java)
        assertThat((error as VirgaError.Auth).message).contains("invalid_grant")
        assertThat(error.message).contains("400")
    }

    @Test
    fun exchange_mapsNonJsonBodyToAuthError() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("not-json"),
        )

        val error = exchanger.exchange(pending(), code = "ok").exceptionOrNull()

        assertThat(error).isInstanceOf(VirgaError.Auth::class.java)
    }

    @Test
    fun exchange_mapsConnectFailureToNetworkError() = runTest {
        // Shutting the server down before exchange forces an IOException.
        val deadUrl = server.url("/dead").toString()
        server.shutdown()
        val dead = provider().copy(tokenEndpoint = deadUrl)

        val error = exchanger.exchange(pending(dead), code = "ok").exceptionOrNull()

        assertThat(error).isInstanceOf(VirgaError.Network::class.java)
    }

    @Test
    fun exchange_neverSendsClientSecret() = runTest {
        // The bundled (Custom Tabs + PKCE) flow is public-client only — no secret
        // is ever sent in the token exchange.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"a","expires_in":3600}""",
            ),
        )

        exchanger.exchange(pending(), code = "auth-code").getOrThrow()

        val body = server.takeRequest().body.readUtf8()
        assertThat(body).doesNotContain("client_secret")
    }

    private object TestDispatchers : DispatcherProvider {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()
        override val main: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
    }
}

@Suppress("unused")
private val _unused = Dispatchers.IO // keep import; some tests below import live in suites
