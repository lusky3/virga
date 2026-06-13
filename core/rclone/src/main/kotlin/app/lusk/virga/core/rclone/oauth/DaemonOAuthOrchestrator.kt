package app.lusk.virga.core.rclone.oauth

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.rclone.RcloneDaemon
import app.lusk.virga.core.rclone.api.RcApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Drives rclone's interactive `config/create` state machine for OAuth providers.
 *
 * Protocol (verified against rclone v1.74.x over the RC API):
 * - Initial call: `{name, type, parameters, opt: {nonInteractive: true, all: true}}`.
 *   Without `nonInteractive` rclone falls back to reading stdin and never returns
 *   the question/answer machine.
 * - Each response is `{State, Option, Error, Result}`; a non-null `Option` is the
 *   next question.
 * - Continuations must repeat `name`/`type`/`parameters` and carry the answer
 *   inside `opt`: `{continue: true, state: <State>, result: <answer>,
 *   nonInteractive: true, all: true}`. Top-level `state`/`result` is rejected
 *   with HTTP 500 `Didn't find key "name" in input`.
 * - Terminal success is `State == ""` with `Option == null`; `Result` is an
 *   empty string (NOT the remote name), so [State.Complete] carries the name
 *   the caller asked for.
 *
 * Auth-URL discovery: we deliberately answer `config_is_local = false` and use
 * the spec-blessed paste-token fallback ([State.AwaitingTokenPaste] +
 * [submitToken]). A spike against a live rclone 1.74 `rcd` daemon proved the
 * automatic branch (`config_is_local = true`) surfaces its
 * `http://127.0.0.1:53682/auth?state=...` URL ONLY as a NOTICE line on the
 * daemon's stderr: no RC endpoint exposes it (`rc/list` has no log or oauth
 * commands — `config/oauthstatus`/`config/oauthstop` do not exist), and issuing
 * the blocking continuation with `_async=true` leaves `job/status.output` null
 * until the redirect lands. Worse, the 53682 redirect handler is one-shot: a
 * single stray HTTP probe consumes it and aborts the whole flow with "No code
 * returned by remote server". With no reliable in-protocol URL discovery, the
 * paste-token path is the only protocol-clean option: rclone replies with a
 * `config_token` question whose `Help` text carries the user instructions
 * (`rclone authorize "<type>" ... then paste the result`).
 */
class DaemonOAuthOrchestrator(
    private val apiClient: RcApiClient,
    private val dispatchers: DispatcherProvider,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    sealed interface State {
        data object Idle : State
        data object Starting : State

        /**
         * Retained for API compatibility. The current flow never emits this:
         * rclone offers no RC-queryable auth URL (see class KDoc), so the
         * orchestrator always takes the paste-token branch instead.
         */
        data class AwaitingAuth(val url: String) : State

        /**
         * rclone asked for a pasted OAuth token (`config_token` question on the
         * `config_is_local = false` branch). [instructions] is rclone's `Help`
         * text telling the user to run `rclone authorize "<type>"` elsewhere and
         * paste the result. Resume the machine with [submitToken].
         */
        data class AwaitingTokenPaste(val instructions: String) : State

        data class Complete(val remoteName: String) : State
        data class Failed(val message: String) : State
        data object TimedOut : State
        data object Cancelled : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var pendingToken: CompletableDeferred<String>? = null

    fun start(
        name: String,
        type: String,
        clientId: String?,
        clientSecret: String?,
        daemon: RcloneDaemon,
        scope: CoroutineScope,
    ) {
        _state.value = State.Starting
        job = scope.launch(dispatchers.io) {
            try {
                withTimeout(timeoutMs) {
                    runStateMachine(daemon, name, type, clientId, clientSecret)
                }
            } catch (_: TimeoutCancellationException) {
                _state.value = State.TimedOut
            } catch (e: CancellationException) {
                _state.value = State.Cancelled
                throw e
            } catch (e: Throwable) {
                _state.value = State.Failed(e.message ?: "Unknown error")
            } finally {
                pendingToken = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * Feeds the OAuth token the user pasted into the waiting state machine.
     * No-op unless the orchestrator is in [State.AwaitingTokenPaste].
     */
    fun submitToken(token: String) {
        pendingToken?.complete(token)
    }

    private suspend fun runStateMachine(
        daemon: RcloneDaemon,
        name: String,
        type: String,
        clientId: String?,
        clientSecret: String?,
    ) {
        val parameters = buildJsonObject {
            if (!clientId.isNullOrBlank()) put("client_id", clientId)
            if (!clientSecret.isNullOrBlank()) put("client_secret", clientSecret)
        }

        var response = rc(daemon, "config/create", buildJsonObject {
            put("name", name)
            put("type", type)
            put("parameters", parameters)
            putJsonObject("opt") {
                put("nonInteractive", true)
                put("all", true)
            }
        })

        var previousStateToken: String? = null
        while (true) {
            val error = response["Error"]?.jsonPrimitive?.contentOrNull
            if (!error.isNullOrBlank()) {
                _state.value = State.Failed(error)
                return
            }

            // Terminal: empty State with no further question. Result is "" on
            // success (verified against rclone 1.74), so report the name we
            // were asked to create.
            val stateToken = response["State"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (stateToken.isEmpty()) {
                _state.value = State.Complete(name)
                return
            }

            // Non-terminal without a question → unexpected protocol response.
            // (`as?` because rclone sends "Option": null, which parses to
            // JsonNull — `.jsonObject` on JsonNull throws.)
            val option = response["Option"] as? JsonObject
            if (option == null) {
                _state.value = State.Failed("Unexpected response from rclone")
                return
            }

            // MED-7 guard: an identical consecutive state token means rclone
            // rejected our answer and re-asked the same question. Bail instead
            // of re-answering identically until the timeout burns out.
            if (stateToken == previousStateToken) {
                _state.value = State.Failed(
                    "rclone re-asked the same question (state \"$stateToken\")",
                )
                return
            }
            previousStateToken = stateToken

            val optionName = option["Name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val answer = when (optionName) {
                // Never answer true: that starts rclone's local 127.0.0.1:53682
                // redirect server, whose auth URL is not discoverable over RC
                // (see class KDoc). "false" routes to the paste-token question.
                "config_is_local" -> "false"
                "config_token" -> awaitPastedToken(option)
                else -> defaultAnswer(option)
            }

            response = rc(daemon, "config/create", buildJsonObject {
                put("name", name)
                put("type", type)
                put("parameters", parameters)
                putJsonObject("opt") {
                    put("nonInteractive", true)
                    put("continue", true)
                    put("all", true)
                    put("state", stateToken)
                    put("result", answer)
                }
            })
        }
    }

    /**
     * Surfaces the `config_token` question as [State.AwaitingTokenPaste] and
     * suspends until [submitToken] supplies the user's pasted token.
     */
    private suspend fun awaitPastedToken(option: JsonObject): String {
        val instructions = option["Help"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val deferred = CompletableDeferred<String>()
        pendingToken = deferred
        _state.value = State.AwaitingTokenPaste(instructions)
        return try {
            deferred.await()
        } finally {
            pendingToken = null
        }
    }

    // TODO(0.3.0): Surface unknown required questions as form fields instead of
    // auto-answering with defaults. Track which providers need this in ProviderCatalog.
    private fun defaultAnswer(option: JsonObject): String {
        // rclone always supplies DefaultStr (the canonical string form of the
        // default, e.g. "false" for bools, "" for strings) — prefer it.
        val defaultStr = option["DefaultStr"]?.jsonPrimitive?.contentOrNull
        if (defaultStr != null) return defaultStr
        val type = option["Type"]?.jsonPrimitive?.contentOrNull
        val default = option["Default"]
        return when {
            type == "bool" -> (default?.jsonPrimitive?.booleanOrNull ?: false).toString()
            default != null -> default.jsonPrimitive.contentOrNull ?: ""
            else -> ""
        }
    }

    private suspend fun rc(
        daemon: RcloneDaemon,
        command: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = apiClient.call(daemon.baseUrl, daemon.user, daemon.pass, command, params)

    companion object {
        const val TIMEOUT_MS = 120_000L
    }
}
