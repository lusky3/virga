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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * All mocked rclone responses below use the REAL `config/create` protocol
 * shapes recorded in a spike against rclone v1.74.3 (`rc --loopback` and a
 * live `rcd` daemon):
 *
 * - Question: `{"State": "<token>", "Option": {Name, Type, DefaultStr, Help, ...},
 *   "Error": "", "Result": ""}`
 * - Continuation request: `{"name", "type", "parameters", "opt": {"nonInteractive":
 *   true, "continue": true, "all": true, "state": "<token>", "result": "<answer>"}}`
 * - Terminal success: `{"State": "", "Option": null, "Error": "", "Result": ""}` —
 *   Result is an empty string, NOT the remote name.
 *
 * `config/oauthstatus` / `config/oauthstop` do not exist in rclone 1.74 and
 * must never be called.
 */
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

    /** Every params object POSTed to config/create, in call order. */
    private val requests = CopyOnWriteArrayList<JsonObject>()

    @BeforeEach
    fun setUp() {
        apiClient = mockk()
        daemon = RcloneDaemon(
            process = mockk { every { isAlive } returns true },
            port = 5572,
            user = "u",
            pass = "p",
        )
        requests.clear()
    }

    // ---------------------------------------------------------------- helpers

    /** A question response as rclone 1.74 actually shapes it. */
    private fun question(
        stateToken: String,
        name: String,
        type: String = "string",
        defaultStr: String = "",
        help: String = "",
    ): JsonObject = buildJsonObject {
        put("State", stateToken)
        put("Error", "")
        put("Result", "")
        putJsonObject("Option") {
            put("Name", name)
            put("Type", type)
            put("DefaultStr", defaultStr)
            put("Help", help)
        }
    }

    /** A question that is Required with no DefaultStr — must surface as AwaitingFieldInput. */
    private fun requiredQuestion(
        stateToken: String,
        name: String,
        help: String = "",
        examples: List<String> = emptyList(),
        isPassword: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("State", stateToken)
        put("Error", "")
        put("Result", "")
        putJsonObject("Option") {
            put("Name", name)
            put("Type", "string")
            put("Required", true)
            put("Help", help)
            put("IsPassword", isPassword)
            put("Default", JsonNull)
            // DefaultStr intentionally absent (required + no default)
            if (examples.isNotEmpty()) {
                putJsonArray("Examples") {
                    examples.forEach { ex -> add(buildJsonObject { put("Value", ex) }) }
                }
            }
        }
    }

    /** Terminal success: empty State, null Option, EMPTY Result. */
    private fun terminal(): JsonObject = buildJsonObject {
        put("State", "")
        put("Error", "")
        put("Result", "")
        put("Option", JsonNull)
    }

    /** Mocks config/create to reply with [responses] in sequence, capturing requests. */
    private fun enqueueResponses(vararg responses: JsonObject) {
        coEvery {
            apiClient.call(any(), any(), any(), "config/create", any())
        } answers {
            requests.add(arg(4))
            responses[(requests.size - 1).coerceAtMost(responses.lastIndex)]
        }
    }

    private fun JsonObject.opt(): JsonObject = getValue("opt").jsonObject

    private fun JsonObject.optStr(key: String): String? =
        opt()[key]?.jsonPrimitive?.contentOrNull

    private fun assertNoInventedEndpointsCalled() {
        coVerify(exactly = 0) {
            apiClient.call(any(), any(), any(), match { it != "config/create" }, any())
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `happy path - walks questions, pastes token, completes`() = runTest(testDispatcher) {
        val pasteHelp = "Execute rclone authorize \"drive\" then paste the result."
        enqueueResponses(
            question("*all-set,0,false", "client_id"),
            question("*oauth-islocal,teamdrive,,", "config_is_local", type = "bool", defaultStr = "true"),
            question("*oauth-authorize,teamdrive,,", "config_token", help = pasteHelp),
            question("teamdrive_ok", "config_change_team_drive", type = "bool", defaultStr = "false"),
            terminal(),
        )

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("myremote", "drive", null, null, daemon, this)
        runCurrent()

        // Machine paused on config_token, surfacing rclone's Help instructions.
        assertThat(orchestrator.state.value).isEqualTo(
            DaemonOAuthOrchestrator.State.AwaitingTokenPaste(pasteHelp),
        )

        orchestrator.submitToken("""{"access_token":"tok"}""")
        advanceUntilIdle()

        // Terminal Result is "" in the real protocol — Complete must carry the
        // requested name, not the (empty) Result.
        assertThat(orchestrator.state.value)
            .isEqualTo(DaemonOAuthOrchestrator.State.Complete("myremote"))

        // Initial request: opt carries nonInteractive+all; no continuation fields,
        // and crucially no top-level state/result (rejected by real rclone).
        val initial = requests[0]
        assertThat(initial["name"]?.jsonPrimitive?.contentOrNull).isEqualTo("myremote")
        assertThat(initial["type"]?.jsonPrimitive?.contentOrNull).isEqualTo("drive")
        assertThat(initial.optStr("nonInteractive")).isEqualTo("true")
        assertThat(initial.optStr("all")).isEqualTo("true")
        assertThat(initial.opt()["continue"]).isNull()
        assertThat(initial["state"]).isNull()
        assertThat(initial["result"]).isNull()

        // Every continuation repeats name/type/parameters and answers inside opt.
        for (req in requests.drop(1)) {
            assertThat(req["name"]?.jsonPrimitive?.contentOrNull).isEqualTo("myremote")
            assertThat(req["type"]?.jsonPrimitive?.contentOrNull).isEqualTo("drive")
            assertThat(req["parameters"]).isInstanceOf(JsonObject::class.java)
            assertThat(req.optStr("continue")).isEqualTo("true")
            assertThat(req.optStr("nonInteractive")).isEqualTo("true")
            assertThat(req.optStr("all")).isEqualTo("true")
        }
        assertThat(requests[1].optStr("state")).isEqualTo("*all-set,0,false")
        assertThat(requests[1].optStr("result")).isEqualTo("")
        // config_is_local is always answered "false" (paste-token branch).
        assertThat(requests[2].optStr("state")).isEqualTo("*oauth-islocal,teamdrive,,")
        assertThat(requests[2].optStr("result")).isEqualTo("false")
        assertThat(requests[3].optStr("state")).isEqualTo("*oauth-authorize,teamdrive,,")
        assertThat(requests[3].optStr("result")).isEqualTo("""{"access_token":"tok"}""")
        assertThat(requests[4].optStr("state")).isEqualTo("teamdrive_ok")
        assertThat(requests[4].optStr("result")).isEqualTo("false")

        assertNoInventedEndpointsCalled()
    }

    @Test
    fun `emits Failed when rclone reports an in-band error`() = runTest(testDispatcher) {
        enqueueResponses(
            buildJsonObject {
                put("State", "")
                put("Error", "couldn't find backend called \"nope\"")
                put("Result", "")
                put("Option", JsonNull)
            },
        )

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("bad", "nope", null, null, daemon, this)
        advanceUntilIdle()

        assertThat(orchestrator.state.value).isEqualTo(
            DaemonOAuthOrchestrator.State.Failed("couldn't find backend called \"nope\""),
        )
    }

    @Test
    fun `emits Failed when the RC call throws`() = runTest(testDispatcher) {
        // Real rclone answers protocol mistakes with HTTP 500, which RcApiClient
        // surfaces as an exception.
        coEvery {
            apiClient.call(any(), any(), any(), "config/create", any())
        } throws RuntimeException("Didn't find key \"name\" in input")

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("x", "drive", null, null, daemon, this)
        advanceUntilIdle()

        assertThat(orchestrator.state.value).isEqualTo(
            DaemonOAuthOrchestrator.State.Failed("Didn't find key \"name\" in input"),
        )
    }

    @Test
    fun `times out while waiting for a pasted token`() = runTest(testDispatcher) {
        enqueueResponses(
            question("*oauth-islocal,x,,", "config_is_local", type = "bool", defaultStr = "true"),
            question("*oauth-authorize,x,,", "config_token"),
        )

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers, timeoutMs = 500)
        orchestrator.start("x", "drive", null, null, daemon, this)
        runCurrent()
        assertThat(orchestrator.state.value)
            .isInstanceOf(DaemonOAuthOrchestrator.State.AwaitingTokenPaste::class.java)

        advanceTimeBy(600)
        advanceUntilIdle()

        assertThat(orchestrator.state.value).isEqualTo(DaemonOAuthOrchestrator.State.TimedOut)
        assertNoInventedEndpointsCalled()
    }

    @Test
    fun `cancel while awaiting token emits Cancelled without extra RC calls`() = runTest(testDispatcher) {
        enqueueResponses(
            question("*oauth-islocal,x,,", "config_is_local", type = "bool", defaultStr = "true"),
            question("*oauth-authorize,x,,", "config_token"),
        )

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("x", "drive", null, null, daemon, this)
        runCurrent()
        assertThat(orchestrator.state.value)
            .isInstanceOf(DaemonOAuthOrchestrator.State.AwaitingTokenPaste::class.java)

        orchestrator.cancel()
        advanceUntilIdle()

        assertThat(orchestrator.state.value).isEqualTo(DaemonOAuthOrchestrator.State.Cancelled)
        // config/oauthstop does not exist in rclone 1.74 — nothing may call it.
        assertNoInventedEndpointsCalled()
    }

    @Test
    fun `passes client_id and client_secret inside parameters`() = runTest(testDispatcher) {
        enqueueResponses(terminal())

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("myremote", "pcloud", "my-id", "my-secret", daemon, this)
        advanceUntilIdle()

        assertThat(requests).isNotEmpty()
        val parameters = requests.first().getValue("parameters").jsonObject
        assertThat(parameters["client_id"]?.jsonPrimitive?.contentOrNull).isEqualTo("my-id")
        assertThat(parameters["client_secret"]?.jsonPrimitive?.contentOrNull).isEqualTo("my-secret")
        assertThat(orchestrator.state.value)
            .isEqualTo(DaemonOAuthOrchestrator.State.Complete("myremote"))
    }

    @Test
    fun `answers ordinary questions with their DefaultStr`() = runTest(testDispatcher) {
        enqueueResponses(
            question("*all-set,6,false", "scope", defaultStr = "drive"),
            question("*all-advanced", "config_fs_advanced", type = "bool", defaultStr = "false"),
            terminal(),
        )

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("mygdrive", "drive", null, null, daemon, this)
        advanceUntilIdle()

        assertThat(requests[1].optStr("result")).isEqualTo("drive")
        assertThat(requests[2].optStr("result")).isEqualTo("false")
        assertThat(orchestrator.state.value)
            .isEqualTo(DaemonOAuthOrchestrator.State.Complete("mygdrive"))
    }

    @Test
    fun `fails when rclone re-asks with the same state token`() = runTest(testDispatcher) {
        // MED-7 guard: identical consecutive State means our answer was rejected;
        // bail instead of re-answering identically until the timeout.
        enqueueResponses(
            question("tok1", "scope", defaultStr = "drive"),
            question("tok1", "scope", defaultStr = "drive"),
        )

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("x", "drive", null, null, daemon, this)
        advanceUntilIdle()

        val state = orchestrator.state.value
        assertThat(state).isInstanceOf(DaemonOAuthOrchestrator.State.Failed::class.java)
        assertThat((state as DaemonOAuthOrchestrator.State.Failed).message).contains("tok1")
        // Exactly 2 calls: initial + one continuation. No infinite re-ask loop.
        assertThat(requests).hasSize(2)
    }

    @Test
    fun `fails on a non-terminal response with null Option`() = runTest(testDispatcher) {
        enqueueResponses(
            buildJsonObject {
                put("State", "tok1")
                put("Error", "")
                put("Result", "")
                put("Option", JsonNull)
            },
        )

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("x", "drive", null, null, daemon, this)
        advanceUntilIdle()

        assertThat(orchestrator.state.value).isEqualTo(
            DaemonOAuthOrchestrator.State.Failed("Unexpected response from rclone"),
        )
    }

    @Test
    fun `required question with no default surfaces AwaitingFieldInput and submitFieldAnswer resumes`() = runTest(testDispatcher) {
        enqueueResponses(
            requiredQuestion("*req-field,0", "access_key_id", help = "AWS Access Key ID"),
            terminal(),
        )

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("mys3", "s3", null, null, daemon, this)
        runCurrent()

        assertThat(orchestrator.state.value).isEqualTo(
            DaemonOAuthOrchestrator.State.AwaitingFieldInput(
                optionName = "access_key_id",
                label = "access_key_id",
                help = "AWS Access Key ID",
                examples = emptyList(),
                isPassword = false,
            ),
        )

        orchestrator.submitFieldAnswer("AKIAIOSFODNN7EXAMPLE")
        advanceUntilIdle()

        assertThat(orchestrator.state.value)
            .isEqualTo(DaemonOAuthOrchestrator.State.Complete("mys3"))
        // Verify the answer was sent as the result in the continuation.
        assertThat(requests[1].optStr("result")).isEqualTo("AKIAIOSFODNN7EXAMPLE")
    }

    @Test
    fun `non-required question auto-answers with default and does not emit AwaitingFieldInput`() = runTest(testDispatcher) {
        // Regression guard: non-required questions must still auto-default — no
        // AwaitingFieldInput may be emitted for them.
        enqueueResponses(
            question("*opt-field,0", "scope", defaultStr = "drive"),
            terminal(),
        )

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("mygdrive", "drive", null, null, daemon, this)
        advanceUntilIdle()

        assertThat(orchestrator.state.value)
            .isEqualTo(DaemonOAuthOrchestrator.State.Complete("mygdrive"))
        // State is Complete, never AwaitingFieldInput (checked via final value alone).
        assertThat(orchestrator.state.value)
            .isNotInstanceOf(DaemonOAuthOrchestrator.State.AwaitingFieldInput::class.java)
        // The default "drive" was sent in the continuation without any user interaction.
        assertThat(requests[1].optStr("result")).isEqualTo("drive")
    }

    @Test
    fun `required question with an array-typed Default auto-defaults without crashing`() = runTest(testDispatcher) {
        // Robustness: a Required option whose Default serializes as a JSON array
        // (CommaSepList/SpaceSepList) must not throw on `.jsonPrimitive`. The
        // structured default counts as usable, so the question auto-answers
        // rather than surfacing AwaitingFieldInput or failing the flow.
        enqueueResponses(
            buildJsonObject {
                put("State", "*opt-list,0")
                put("Error", "")
                put("Result", "")
                putJsonObject("Option") {
                    put("Name", "exclude")
                    put("Type", "CommaSepList")
                    put("Required", true)
                    put("DefaultStr", "")
                    putJsonArray("Default") {}
                    put("Help", "")
                }
            },
            terminal(),
        )

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("mys3", "s3", null, null, daemon, this)
        advanceUntilIdle()

        assertThat(orchestrator.state.value)
            .isEqualTo(DaemonOAuthOrchestrator.State.Complete("mys3"))
        assertThat(orchestrator.state.value)
            .isNotInstanceOf(DaemonOAuthOrchestrator.State.AwaitingFieldInput::class.java)
    }

    @Test
    fun `required question with a malformed Examples entry surfaces AwaitingFieldInput without crashing`() = runTest(testDispatcher) {
        // Robustness: a non-object entry in Examples must be skipped, not crash
        // the flow on the `.jsonObject` accessor.
        enqueueResponses(
            buildJsonObject {
                put("State", "*opt-field,0")
                put("Error", "")
                put("Result", "")
                putJsonObject("Option") {
                    put("Name", "access_key_id")
                    put("Type", "string")
                    put("Required", true)
                    put("Help", "Access key")
                    put("Default", JsonNull)
                    putJsonArray("Examples") {
                        add(JsonPrimitive("not-an-object"))
                        add(buildJsonObject { put("Value", "AKIA") })
                    }
                }
            },
        )

        val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
        orchestrator.start("mys3", "s3", null, null, daemon, this)
        // runCurrent (not advanceUntilIdle) so virtual time doesn't advance past the
        // orchestrator's timeout while it suspends awaiting the (never-supplied) answer.
        runCurrent()

        val state = orchestrator.state.value
        assertThat(state).isInstanceOf(DaemonOAuthOrchestrator.State.AwaitingFieldInput::class.java)
        assertThat((state as DaemonOAuthOrchestrator.State.AwaitingFieldInput).examples)
            .containsExactly("AKIA")
    }
}
