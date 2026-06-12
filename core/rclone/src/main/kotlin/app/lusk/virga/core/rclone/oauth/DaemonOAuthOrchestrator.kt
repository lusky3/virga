package app.lusk.virga.core.rclone.oauth

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.rclone.RcloneDaemon
import app.lusk.virga.core.rclone.api.RcApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Drives rclone's interactive `config/create` state machine for OAuth providers.
 *
 * The key concurrency challenge: answering `config_is_local = true` causes the
 * next `config/create` POST to block until the OAuth redirect lands on
 * `127.0.0.1:53682`. A poller coroutine polls `config/oauthstatus` concurrently
 * to discover the auth URL, which is surfaced via [state] as [State.AwaitingAuth].
 */
class DaemonOAuthOrchestrator(
    private val apiClient: RcApiClient,
    private val dispatchers: DispatcherProvider,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {
    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class AwaitingAuth(val url: String) : State
        data class Complete(val remoteName: String) : State
        data class Failed(val message: String) : State
        data object TimedOut : State
        data object Cancelled : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var daemon: RcloneDaemon? = null

    fun start(
        name: String,
        type: String,
        clientId: String?,
        clientSecret: String?,
        daemon: RcloneDaemon,
        scope: CoroutineScope,
    ) {
        this.daemon = daemon
        _state.value = State.Starting
        job = scope.launch(dispatchers.io) {
            try {
                withTimeout(timeoutMs) {
                    runStateMachine(daemon, name, type, clientId, clientSecret)
                }
            } catch (_: TimeoutCancellationException) {
                callOAuthStop(daemon)
                _state.value = State.TimedOut
            } catch (e: CancellationException) {
                withContext(NonCancellable) { callOAuthStop(daemon) }
                _state.value = State.Cancelled
                throw e
            } catch (e: Throwable) {
                _state.value = State.Failed(e.message ?: "Unknown error")
            } finally {
                this@DaemonOAuthOrchestrator.daemon = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun runStateMachine(
        daemon: RcloneDaemon,
        name: String,
        type: String,
        clientId: String?,
        clientSecret: String?,
    ) {
        val initialParams = buildJsonObject {
            put("name", name)
            put("type", type)
            putJsonObject("parameters") {
                if (!clientId.isNullOrBlank()) put("client_id", clientId)
                if (!clientSecret.isNullOrBlank()) put("client_secret", clientSecret)
            }
            putJsonObject("opt") { put("all", true) }
        }

        var response = rc(daemon, "config/create", initialParams)

        while (true) {
            val stateToken = response["State"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val result = response["Result"]?.jsonPrimitive?.contentOrNull

            // Terminal: empty State + non-null Result → success.
            if (stateToken.isEmpty() && result != null) {
                _state.value = State.Complete(result)
                return
            }

            // Terminal: Error field.
            val error = response["Error"]?.jsonPrimitive?.contentOrNull
            if (!error.isNullOrBlank()) {
                _state.value = State.Failed(error)
                return
            }

            // No option and no terminal → unexpected protocol response.
            val option = response["Option"]?.jsonObject
            if (option == null) {
                _state.value = State.Failed("Unexpected response from rclone")
                return
            }

            val optionName = option["Name"]?.jsonPrimitive?.contentOrNull.orEmpty()

            if (optionName == "config_is_local") {
                // Launch the oauthstatus poller BEFORE the blocking answer.
                val scope = CoroutineScope(currentCoroutineContext())
                val pollerJob = scope.launch { pollForAuthUrl(daemon) }
                try {
                    // This call BLOCKS until the OAuth redirect arrives.
                    response = rc(daemon, "config/create", buildJsonObject {
                        put("state", stateToken)
                        put("result", "true")
                    })
                } finally {
                    pollerJob.cancel()
                }
            } else {
                val answer = defaultAnswer(option)
                response = rc(daemon, "config/create", buildJsonObject {
                    put("state", stateToken)
                    put("result", answer)
                })
            }
        }
    }

    // TODO(0.3.0): Surface unknown required questions as form fields instead of
    // auto-answering with defaults. Track which providers need this in ProviderCatalog.
    private fun defaultAnswer(option: JsonObject): String {
        val type = option["Type"]?.jsonPrimitive?.contentOrNull
        val default = option["Default"]
        return when {
            type == "bool" -> (default?.jsonPrimitive?.booleanOrNull ?: false).toString()
            default != null -> default.jsonPrimitive.contentOrNull ?: ""
            else -> ""
        }
    }

    private suspend fun pollForAuthUrl(daemon: RcloneDaemon) {
        while (currentCoroutineContext().isActive) {
            val status = runCatching { rc(daemon, "config/oauthstatus") }.getOrNull()
            val url = status?.get("url")?.jsonPrimitive?.contentOrNull
            if (!url.isNullOrBlank()) {
                _state.value = State.AwaitingAuth(url)
                return
            }
            delay(pollIntervalMs)
        }
    }

    private suspend fun callOAuthStop(daemon: RcloneDaemon) {
        runCatching { rc(daemon, "config/oauthstop") }
    }

    private suspend fun rc(
        daemon: RcloneDaemon,
        command: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = apiClient.call(daemon.baseUrl, daemon.user, daemon.pass, command, params)

    companion object {
        const val TIMEOUT_MS = 120_000L
        const val POLL_INTERVAL_MS = 200L
    }
}
