package app.lusk.virga.core.rclone.oauth

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.rclone.RcloneDaemon
import app.lusk.virga.core.rclone.api.RcApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * On-device OAuth (default): we answer `config_is_local = true`, which starts
 * rclone's loopback redirect server on `127.0.0.1:<port>`. rclone then logs the
 * auth URL as a NOTICE line on its stderr (no RC endpoint exposes it). The manager
 * drains that stderr into [RcloneDaemon.stderrLines]; [runStateMachine] subscribes
 * to that flow BEFORE sending the blocking continuation (to avoid missing the URL),
 * extracts the URL via [extractAuthUrl], and emits [State.AwaitingAuth] so the
 * feature layer can open it in a Custom Tab. rclone's loopback server catches the
 * provider redirect and completes the flow; the blocking continuation then returns.
 *
 * The loopback handler is one-shot — a stray HTTP probe would consume it. The
 * orchestrator never probes it; only the Custom Tab the user opens does.
 *
 * Paste-token fallback: pass [forcePasteToken]=true to [start] to answer
 * `config_is_local = false` instead, which routes to the `config_token` question
 * ([State.AwaitingTokenPaste] + [submitToken]). Used for providers whose loopback
 * redirect doesn't work on-device, or when the user explicitly requests it.
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
         * The on-device OAuth URL is ready. The feature layer should open [url]
         * in a Custom Tab; rclone's loopback server will catch the provider's
         * redirect and complete the flow automatically.
         */
        data class AwaitingAuth(val url: String) : State

        /**
         * rclone asked for a pasted OAuth token (`config_token` question on the
         * `config_is_local = false` branch). [instructions] is rclone's `Help`
         * text telling the user to run `rclone authorize "<type>"` elsewhere and
         * paste the result. Resume the machine with [submitToken].
         */
        data class AwaitingTokenPaste(val instructions: String) : State

        /**
         * rclone asked a question that is marked Required with no usable default.
         * The user must supply a value; resume the machine with [submitFieldAnswer].
         * [label] is the option's Name (or a humanized form), [help] is rclone's
         * Help text, [examples] is the list of example values (may be empty), and
         * [isPassword] signals whether the input should be masked.
         */
        data class AwaitingFieldInput(
            val optionName: String,
            val label: String,
            val help: String,
            val examples: List<String> = emptyList(),
            val isPassword: Boolean = false,
        ) : State

        data class Complete(val remoteName: String) : State
        data class Failed(val message: String) : State
        data object TimedOut : State
        data object Cancelled : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var pendingToken: CompletableDeferred<String>? = null
    private var pendingFieldAnswer: CompletableDeferred<String>? = null

    fun start(
        name: String,
        type: String,
        clientId: String?,
        clientSecret: String?,
        daemon: RcloneDaemon,
        scope: CoroutineScope,
        forcePasteToken: Boolean = false,
    ) {
        _state.value = State.Starting
        job = scope.launch(dispatchers.io) {
            try {
                withTimeout(timeoutMs) {
                    runStateMachine(daemon, name, type, clientId, clientSecret, forcePasteToken)
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
                pendingFieldAnswer = null
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

    /**
     * Feeds the field answer supplied by the user into the waiting state machine.
     * No-op unless the orchestrator is in [State.AwaitingFieldInput].
     */
    fun submitFieldAnswer(answer: String) {
        pendingFieldAnswer?.complete(answer)
    }

    private suspend fun runStateMachine(
        daemon: RcloneDaemon,
        name: String,
        type: String,
        clientId: String?,
        clientSecret: String?,
        forcePasteToken: Boolean,
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

            // On-device flow: answer "true" so rclone starts its loopback redirect
            // server and logs the auth URL on stderr. We scrape that URL and open it
            // in a Custom Tab; rclone's loopback catches the redirect and returns.
            // forcePasteToken selects the legacy "false" branch instead.
            if (optionName == "config_is_local") {
                if (forcePasteToken) {
                    response = sendContinue(daemon, name, type, parameters, stateToken, "false")
                } else {
                    val continueResult = awaitOnDeviceAuth(daemon, name, type, parameters, stateToken)
                    if (continueResult == null) return // state already set to Failed
                    response = continueResult
                }
                continue
            }

            val answer = when (optionName) {
                "config_token" -> awaitPastedToken(option)
                else -> if (requiresUserInput(option)) awaitFieldInput(option) else defaultAnswer(option)
            }

            response = sendContinue(daemon, name, type, parameters, stateToken, answer)
        }
    }

    /**
     * Handles the `config_is_local = true` branch for on-device OAuth.
     *
     * Race mitigation: the stderr collector ([urlDeferred]) is started and given
     * a scheduling opportunity via [yield] BEFORE the blocking continuation RC
     * call is dispatched. Because both run on the same coroutine dispatcher in
     * tests (StandardTestDispatcher) and on IO threads in production, `yield`
     * ensures the async block has entered its `collect` before any bytes from
     * the continuation can arrive. This prevents the URL line from being missed
     * on a SharedFlow with replay=0.
     *
     * Returns the RC response that follows the redirect (to continue the state
     * machine), or null when the URL couldn't be found (failure state already set).
     */
    private suspend fun awaitOnDeviceAuth(
        daemon: RcloneDaemon,
        name: String,
        type: String,
        parameters: JsonObject,
        stateToken: String,
    ): JsonObject? = coroutineScope {
        // Start scraping stderr for the auth URL BEFORE sending the continue so
        // we don't miss the URL line on a replay=0 SharedFlow.
        val urlDeferred = async {
            withTimeoutOrNull(AUTH_URL_TIMEOUT_MS) {
                // mapNotNull transforms String lines to non-null URL strings; first()
                // suspends until the first non-null value arrives.
                daemon.stderrLines
                    .mapNotNull { line: String -> extractAuthUrl(line) }
                    .first()
            }
        }
        // Yield so the async block above enters its collect before we block on rc().
        yield()

        val rcDeferred = async {
            sendContinue(daemon, name, type, parameters, stateToken, "true")
        }

        val url = urlDeferred.await()
        if (url == null) {
            rcDeferred.cancel()
            _state.value = State.Failed(
                "Couldn't start on-device sign-in. Try authorizing on another device.",
            )
            return@coroutineScope null
        }

        _state.value = State.AwaitingAuth(url)
        rcDeferred.await()
    }

    private suspend fun sendContinue(
        daemon: RcloneDaemon,
        name: String,
        type: String,
        parameters: JsonObject,
        stateToken: String,
        answer: String,
    ): JsonObject = rc(daemon, "config/create", buildJsonObject {
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

    /**
     * Surfaces a required-no-default question as [State.AwaitingFieldInput] and
     * suspends until [submitFieldAnswer] supplies the user's answer.
     */
    private suspend fun awaitFieldInput(option: JsonObject): String {
        val name = option["Name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val help = option["Help"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val isPassword = option["IsPassword"]?.jsonPrimitive?.booleanOrNull ?: false
        val examples = (option["Examples"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("Value")?.jsonPrimitive?.contentOrNull }
            ?: emptyList()
        val deferred = CompletableDeferred<String>()
        pendingFieldAnswer = deferred
        _state.value = State.AwaitingFieldInput(
            optionName = name,
            label = name,
            help = help,
            examples = examples,
            isPassword = isPassword,
        )
        return try {
            deferred.await()
        } finally {
            pendingFieldAnswer = null
        }
    }

    /**
     * True when a question must be surfaced to the user: it is marked Required
     * AND has no usable default. The Required+no-default signal comes from rclone
     * directly — no ProviderCatalog-based tracking is needed.
     */
    private fun requiresUserInput(option: JsonObject): Boolean {
        val required = option["Required"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!required) return false
        val defaultStr = option["DefaultStr"]?.jsonPrimitive?.contentOrNull
        if (!defaultStr.isNullOrBlank()) return false
        return !hasUsableDefault(option["Default"])
    }

    /**
     * True when [default] carries a usable value. A structured default (a
     * `CommaSepList`/`SpaceSepList` serializes as a JSON array) counts as usable;
     * crucially we must NOT call `.jsonPrimitive` on a non-primitive, which throws.
     */
    private fun hasUsableDefault(default: JsonElement?): Boolean = when {
        default == null || default is JsonNull -> false
        default is JsonPrimitive -> !default.contentOrNull.isNullOrBlank()
        else -> true
    }

    private fun defaultAnswer(option: JsonObject): String {
        // rclone always supplies DefaultStr (the canonical string form of the
        // default, e.g. "false" for bools, "" for strings) — prefer it.
        val defaultStr = option["DefaultStr"]?.jsonPrimitive?.contentOrNull
        if (defaultStr != null) return defaultStr
        val type = option["Type"]?.jsonPrimitive?.contentOrNull
        // `as? JsonPrimitive` guards against array/object defaults, which would
        // throw on `.jsonPrimitive`.
        val default = option["Default"] as? JsonPrimitive
        return when {
            type == "bool" -> (default?.booleanOrNull ?: false).toString()
            default != null -> default.contentOrNull ?: ""
            else -> ""
        }
    }

    /**
     * Attempts to extract the loopback auth URL from a raw stderr line.
     *
     * rclone uses `--use-json-log`, so lines are JSON objects with a `msg` field:
     * ```json
     * {"level":"notice","msg":"Go to this URL…\nhttp://127.0.0.1:53682/auth?state=abc","time":"…"}
     * ```
     * The function first tries JSON parsing and reads `msg`; if that fails (e.g. a
     * non-JSON startup line) it falls back to regexing the raw line. Returns null
     * when no loopback auth URL is found.
     */
    internal fun extractAuthUrl(line: String): String? {
        val searchIn = try {
            Json.parseToJsonElement(line).jsonObject["msg"]?.jsonPrimitive?.contentOrNull ?: line
        } catch (_: Throwable) {
            line
        }
        return AUTH_URL_REGEX.find(searchIn)?.value
    }

    private suspend fun rc(
        daemon: RcloneDaemon,
        command: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = apiClient.call(daemon.baseUrl, daemon.user, daemon.pass, command, params)

    companion object {
        /**
         * Overall state-machine timeout. The on-device flow requires the user to
         * complete browser-based OAuth (open URL → sign in → grant → redirect), so
         * 3 minutes is the minimum useful backstop. The feature layer ([DaemonOAuthFlow])
         * overrides with 600 s for the paste-token path, which is fine — this is only
         * the orchestrator's own safety net.
         */
        const val TIMEOUT_MS = 180_000L

        /**
         * How long to wait for rclone to log the loopback auth URL after answering
         * `config_is_local = true`. rclone starts its redirect server synchronously
         * before logging the URL, so 20 s is ample; failure here means the daemon
         * didn't start the server (e.g. port conflict) and we should fall back.
         */
        const val AUTH_URL_TIMEOUT_MS = 20_000L

        // Matches the loopback auth URL rclone prints to stderr, e.g.:
        //   http://127.0.0.1:53682/auth?state=abc123
        // The URL appears inside a JSON "msg" field (--use-json-log) or as plain text.
        private val AUTH_URL_REGEX = Regex("""https?://127\.0\.0\.1:\d+/auth\?[^\s"]+""")
    }
}
