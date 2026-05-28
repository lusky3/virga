package app.lusk.virga.core.rclone.api

import app.lusk.virga.core.common.error.VirgaError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RcApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RcApiClient

    @BeforeEach fun setUp() {
        server = MockWebServer()
        server.start()
        client = RcApiClient(OkHttpClient())
    }

    @AfterEach fun tearDown() = server.shutdown()

    private val baseUrl get() = server.url("").toString().trimEnd('/')

    // --- Happy path ---

    @Test fun `call returns parsed JsonObject on 200`() = runTest {
        server.enqueue(MockResponse().setBody("""{"foo":"bar"}""").setResponseCode(200))

        val result = client.call(baseUrl, "user", "pass", "rc/noop")

        assertThat(result["foo"]).isEqualTo(JsonPrimitive("bar"))
    }

    @Test fun `call sends Authorization header`() = runTest {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        client.call(baseUrl, "alice", "s3cr3t", "rc/noop")

        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).startsWith("Basic ")
    }

    @Test fun `call sends JSON body`() = runTest {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))
        val params = buildJsonObject { put("key", "value") }

        client.call(baseUrl, "u", "p", "config/listremotes", params)

        val recorded = server.takeRequest()
        assertThat(recorded.body.readUtf8()).contains("\"key\"")
    }

    @Test fun `call builds URL from baseUrl and command`() = runTest {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        client.call(baseUrl, "u", "p", "operations/list")

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/operations/list")
    }

    @Test fun `call returns empty JsonObject on 200 with blank body`() = runTest {
        server.enqueue(MockResponse().setBody("").setResponseCode(200))

        val result = client.call(baseUrl, "u", "p", "rc/noop")

        assertThat(result).isEqualTo(JsonObject(emptyMap()))
    }

    // --- Error path ---

    @Test fun `call throws VirgaError_Rclone on non-2xx with JSON error field`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"error":"permission denied"}""")
            .setResponseCode(403))

        val ex = assertThrows<VirgaError.Rclone> {
            client.call(baseUrl, "u", "p", "config/delete")
        }
        assertThat(ex.exitCode).isEqualTo(403)
        assertThat(ex.message).contains("permission denied")
    }

    @Test fun `call throws VirgaError_Rclone on 500 without JSON`() = runTest {
        server.enqueue(MockResponse().setBody("Internal Server Error").setResponseCode(500))

        val ex = assertThrows<VirgaError.Rclone> {
            client.call(baseUrl, "u", "p", "sync/sync")
        }
        assertThat(ex.exitCode).isEqualTo(500)
    }

    @Test fun `call throws VirgaError_Network on connection refused`() = runTest {
        server.shutdown() // force connection failure

        assertThrows<VirgaError.Network> {
            client.call("http://127.0.0.1:1", "u", "p", "rc/noop")
        }
    }

    @Test fun `call throws VirgaError_Rclone on 401 unauthorized`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"error":"unauthorized"}""")
            .setResponseCode(401))

        val ex = assertThrows<VirgaError.Rclone> {
            client.call(baseUrl, "wrong", "creds", "rc/noop")
        }
        assertThat(ex.exitCode).isEqualTo(401)
    }
}
