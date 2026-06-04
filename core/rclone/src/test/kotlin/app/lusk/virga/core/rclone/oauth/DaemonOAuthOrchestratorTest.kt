package app.lusk.virga.core.rclone.oauth

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.rclone.RcloneDaemon
import app.lusk.virga.core.rclone.api.RcApiClient
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class DaemonOAuthOrchestratorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main get() = testDispatcher
        override val default get() = testDispatcher
        override val io get() = testDispatcher
    }

    private lateinit var apiClient: RcApiClient
    private lateinit var daemon: RcloneDaemon

    /** Track which RC commands were called and with what state transitions. */
    private val calledCommands = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun setUp() {
        apiClient = mockk()
        daemon = RcloneDaemon(
            process = mockk { every { isAlive } returns true },
            port = 5572,
            user = "u",
            pass = "p",
        )
        calledCommands.clear()
    }

    @Test
    fun `happy path - single question then OAuth completes`() = runTest(testDispatcher) {
        var configCreateCalls = 0
        coEvery {
            apiClient.call(any(), any(), any(), "config/create", any())
        } answers {
            configCreateCalls++
            when (configCreateCalls) {
                1 -> buildJsonObject {
                    put("State", "tok1")
                    putJsonObject("Option") {
                        put("Name", "config_is_local")
                        put("Type", "bool")
                        put("Default", true)
                    }
                }
                else -> buildJsonObject {
                    put("State", "")
                    put("Result", "myremote")
                }
            }
        }

        coEvery {
            apiClient.call(any(), any(), any(), "config/oauthstatus", any())
        } returns buildJsonObject { put("url", "https://pcloud.example/oauth") }

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("myremote", "pcloud", null, null, daemon, this)
        advanceUntilIdle()

        // Verify terminal state — Complete means the full state machine ran successfully.
        assertThat(orchestrator.state.value).isEqualTo(DaemonOAuthOrchestrator.State.Complete("myremote"))
    }

    @Test
    fun `emits Failed when config_create returns an error`() = runTest(testDispatcher) {
        coEvery {
            apiClient.call(any(), any(), any(), "config/create", any())
        } returns buildJsonObject {
            put("State", "")
            put("Error", "remote already exists")
        }

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("bad", "pcloud", null, null, daemon, this)
        advanceUntilIdle()

        assertThat(orchestrator.state.value).isEqualTo(
            DaemonOAuthOrchestrator.State.Failed("remote already exists"),
        )
    }

    @Test
    fun `cancel calls oauthstop`() = runTest(testDispatcher) {
        var configCreateCalls = 0
        coEvery {
            apiClient.call(any(), any(), any(), "config/create", any())
        } coAnswers {
            configCreateCalls++
            when (configCreateCalls) {
                1 -> buildJsonObject {
                    put("State", "tok1")
                    putJsonObject("Option") {
                        put("Name", "config_is_local")
                        put("Type", "bool")
                        put("Default", true)
                    }
                }
                else -> {
                    kotlinx.coroutines.delay(10_000L)
                    buildJsonObject {}
                }
            }
        }

        coEvery {
            apiClient.call(any(), any(), any(), "config/oauthstatus", any())
        } returns buildJsonObject {}

        coEvery {
            apiClient.call(any(), any(), any(), "config/oauthstop", any())
        } returns buildJsonObject {}

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("myremote", "pcloud", null, null, daemon, this)
        advanceTimeBy(500)
        orchestrator.cancel()
        advanceUntilIdle()

        coVerify { apiClient.call(any(), any(), any(), "config/oauthstop", any()) }
    }

    @Test
    fun `passes client_id and client_secret as parameters when provided`() = runTest(testDispatcher) {
        val capturedParams = CopyOnWriteArrayList<JsonObject>()
        coEvery {
            apiClient.call(any(), any(), any(), "config/create", any())
        } answers {
            capturedParams.add(arg(4))
            buildJsonObject {
                put("State", "")
                put("Result", "myremote")
            }
        }

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("myremote", "pcloud", "my-id", "my-secret", daemon, this)
        advanceUntilIdle()

        assertThat(capturedParams).isNotEmpty()
        val initial = capturedParams.first()
        val parameters = initial["parameters"] as? JsonObject
        assertThat(parameters?.get("client_id")?.toString()?.trim('"')).isEqualTo("my-id")
        assertThat(parameters?.get("client_secret")?.toString()?.trim('"')).isEqualTo("my-secret")
    }

    @Test
    fun `times out after configured duration and calls oauthstop`() = runTest(testDispatcher) {
        var configCreateCalls = 0
        coEvery {
            apiClient.call(any(), any(), any(), "config/create", any())
        } coAnswers {
            configCreateCalls++
            when (configCreateCalls) {
                1 -> buildJsonObject {
                    put("State", "tok1")
                    putJsonObject("Option") {
                        put("Name", "config_is_local")
                        put("Type", "bool")
                        put("Default", true)
                    }
                }
                else -> {
                    kotlinx.coroutines.delay(10_000L)
                    buildJsonObject {}
                }
            }
        }

        coEvery {
            apiClient.call(any(), any(), any(), "config/oauthstatus", any())
        } returns buildJsonObject {}

        coEvery {
            apiClient.call(any(), any(), any(), "config/oauthstop", any())
        } returns buildJsonObject {}

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers, timeoutMs = 500, pollIntervalMs = 50)
        orchestrator.start("x", "pcloud", null, null, daemon, this)
        advanceTimeBy(600)
        advanceUntilIdle()

        assertThat(orchestrator.state.value).isEqualTo(DaemonOAuthOrchestrator.State.TimedOut)
        coVerify { apiClient.call(any(), any(), any(), "config/oauthstop", any()) }
    }

    @Test
    fun `answers non-OAuth questions with defaults`() = runTest(testDispatcher) {
        var configCreateCalls = 0
        val capturedAnswers = mutableMapOf<String, String>()

        coEvery {
            apiClient.call(any(), any(), any(), "config/create", any())
        } answers {
            configCreateCalls++
            val params = arg<JsonObject>(4)
            if (configCreateCalls == 2) {
                capturedAnswers["scope"] = params["result"]?.toString()?.trim('"') ?: ""
            }
            when (configCreateCalls) {
                1 -> buildJsonObject {
                    put("State", "tok1")
                    putJsonObject("Option") {
                        put("Name", "scope")
                        put("Type", "string")
                        put("Default", "drive")
                    }
                }
                2 -> buildJsonObject {
                    put("State", "tok2")
                    putJsonObject("Option") {
                        put("Name", "config_is_local")
                        put("Type", "bool")
                        put("Default", true)
                    }
                }
                else -> buildJsonObject {
                    put("State", "")
                    put("Result", "mygdrive")
                }
            }
        }

        coEvery {
            apiClient.call(any(), any(), any(), "config/oauthstatus", any())
        } returns buildJsonObject { put("url", "https://drive.example/oauth") }

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("mygdrive", "drive", null, null, daemon, this)
        advanceUntilIdle()

        assertThat(capturedAnswers["scope"]).isEqualTo("drive")
        assertThat(orchestrator.state.value).isEqualTo(DaemonOAuthOrchestrator.State.Complete("mygdrive"))
    }

    @Test
    fun `answers boolean questions with their default as string`() = runTest(testDispatcher) {
        var configCreateCalls = 0
        val capturedAnswers = mutableMapOf<String, String>()

        coEvery {
            apiClient.call(any(), any(), any(), "config/create", any())
        } answers {
            configCreateCalls++
            val params = arg<JsonObject>(4)
            if (configCreateCalls == 2) {
                capturedAnswers["advanced_config"] = params["result"]?.toString()?.trim('"') ?: ""
            }
            when (configCreateCalls) {
                1 -> buildJsonObject {
                    put("State", "tok1")
                    putJsonObject("Option") {
                        put("Name", "advanced_config")
                        put("Type", "bool")
                        put("Default", false)
                    }
                }
                else -> buildJsonObject {
                    put("State", "")
                    put("Result", "x")
                }
            }
        }

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("x", "drive", null, null, daemon, this)
        advanceUntilIdle()

        assertThat(capturedAnswers["advanced_config"]).isEqualTo("false")
    }

    @Test
    fun `answers post-OAuth questions with defaults until terminal`() = runTest(testDispatcher) {
        var configCreateCalls = 0

        coEvery {
            apiClient.call(any(), any(), any(), "config/create", any())
        } answers {
            configCreateCalls++
            when (configCreateCalls) {
                1 -> buildJsonObject {
                    put("State", "tok1")
                    putJsonObject("Option") {
                        put("Name", "config_is_local")
                        put("Type", "bool")
                        put("Default", true)
                    }
                }
                2 -> buildJsonObject {
                    put("State", "tok2")
                    putJsonObject("Option") {
                        put("Name", "config_drive_id")
                        put("Type", "string")
                        put("Default", "")
                    }
                }
                else -> buildJsonObject {
                    put("State", "")
                    put("Result", "mygdrive")
                }
            }
        }

        coEvery {
            apiClient.call(any(), any(), any(), "config/oauthstatus", any())
        } returns buildJsonObject { put("url", "https://drive.example/oauth") }

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("mygdrive", "drive", null, null, daemon, this)
        advanceUntilIdle()

        assertThat(orchestrator.state.value).isEqualTo(DaemonOAuthOrchestrator.State.Complete("mygdrive"))
    }
}
