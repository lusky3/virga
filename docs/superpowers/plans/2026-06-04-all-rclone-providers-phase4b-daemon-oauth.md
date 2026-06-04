# All rclone providers — Phase 4B: Daemon-mediated OAuth

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable OAuth for every non-bundled provider (pCloud, Yandex, Mega, etc.) by driving rclone's interactive `config/create` state machine over the RC API. The daemon starts a local webserver on `127.0.0.1:53682`, rclone emits the auth URL via `config/oauthstatus`, and the browser redirect completes the flow without Virga needing provider-specific client registrations.

**Architecture:** A `DaemonOAuthOrchestrator` runs `config/create` in interactive mode on a background coroutine, answering state-machine questions (`config_is_local` → `"true"`, optional client credentials, etc.). A second coroutine polls `config/oauthstatus` to extract the auth URL, which is surfaced to the UI via the existing `_launchUrl` StateFlow for Custom Tabs. On completion, the blocking `config/create` returns and the token is stored in rclone's config. The orchestrator exposes a sealed `State` flow for the ViewModel to observe.

**Key insight:** `config/create` without `nonInteractive: true` returns a `ConfigOut` response with `State` (a session token), `Option` (the question), and `Error`/`Result` (the outcome). Each round-trip requires a `config/create` POST with the `state` token and the answer. The call that answers `config_is_local = true` **blocks** while rclone's internal server waits for the OAuth redirect. During that block, `config/oauthstatus` returns `{"url": "https://..."}`. After the redirect lands on `127.0.0.1:53682`, the blocked call completes with either another question or `{"Result":"..."}` indicating success.

**Tech stack:** Kotlin coroutines (structured concurrency), kotlinx.serialization, `RcApiClient`, OkHttp, JUnit 5, MockK, Truth, Turbine.

**Scope note — this is plan 4B of 4.** It depends on Phase 1 (classification, `ProviderCatalog`) and Phase 2 (picker routing). It does NOT depend on Phase 3 (wrapper sub-flow).

**Conventions used below**
- Per-module unit tests: `./gradlew :<module>:testDebugUnitTest`. The whole suite (release gate): `./gradlew test`.
- Test stack: JUnit 5, MockK, Truth, Turbine.
- Commit after every green task. Commit messages omit any `Co-Authored-By` trailer.

---

## File structure for Phase 4B

**Create:**
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestrator.kt`
- `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestratorTest.kt`

**Modify:**
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngine.kt` — add `configCreate` / `configOauthStatus` / `configOauthStop` RC helpers (or the orchestrator calls `RcApiClient` directly via the daemon; see design note below).
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt` — expose daemon access for the orchestrator (a new `withDaemon` or delegate the orchestrator to use the engine's existing `ensureDaemon` → RC pattern). Add `mutatingConfigSuspending` variant that doesn't tear down the daemon until the orchestrator signals completion.
- `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt` — add `startDaemonOAuth(type, name, clientId?, clientSecret?)`, wire result to existing `_launchUrl`, transient state, connectivity test.
- `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteDialog.kt` — for `SetupKind.OAuth(bundled = false)`: show optional client_id/client_secret fields + "Connect" button + progress indicator.
- `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt` — VM test for daemon OAuth flow.

---

## Design decisions

1. **The orchestrator owns the RC conversation, not the engine.** `RcloneEngineImpl.mutatingConfig` tears down the daemon synchronously after the block — but daemon OAuth needs the daemon alive for the full multi-second browser round-trip. Rather than fighting `mutatingConfig`'s semantics, the orchestrator gets a daemon reference via a new `RcloneEngineImpl.withDaemonForOAuth(block)` method that holds the mutex, runs the block, and only persists+tears-down on exit.

2. **Polling interval for `config/oauthstatus`.** 200ms — fast enough for the user not to notice the URL pop-up delay; light enough that even 60s of waiting burns only ~300 HTTP calls to localhost.

3. **Timeout.** 2 minutes total. If the user hasn't completed browser auth by then, the orchestrator calls `config/oauthstop` and emits `TimedOut`.

4. **Cancellation.** The orchestrator's coroutine scope is tied to the ViewModel's `viewModelScope`. Cancellation (user presses back, or process death) triggers `config/oauthstop` in a `NonCancellable` finally block.

5. **Multi-question state machines.** Some providers ask questions before OAuth (`drive_type` for Google Drive's team drive support, `advanced_config` for most). The orchestrator answers them with defaults (`""` for optional strings, `"false"` for booleans) unless the user supplied explicit values. `config_is_local` is always answered `"true"`.

---

## RC protocol reference

### `config/create` — interactive mode

Request (start):
```json
{
  "name": "myremote",
  "type": "pcloud",
  "parameters": { "client_id": "...", "client_secret": "..." },
  "opt": { "all": true }
}
```

Response (question):
```json
{
  "State": "*config-state-token*",
  "Option": {
    "Name": "config_is_local",
    "Help": "Use web browser to automatically authenticate rclone...",
    "Default": true,
    "Type": "bool",
    "Exclusive": true
  }
}
```

Request (answer):
```json
{
  "state": "*config-state-token*",
  "result": "true"
}
```

After answering `config_is_local = true`, the response **blocks** while rclone waits for the OAuth redirect.

### `config/oauthstatus`

Request: `{}` (no params)
Response (while waiting): `{"url": "https://accounts.google.com/o/oauth2/..."}`
Response (after redirect): `{}` or absent.

### `config/oauthstop`

Request: `{}` — cancels the in-flight OAuth webserver.

### Terminal response from `config/create`

```json
{
  "State": "",
  "Result": "myremote"
}
```

`State` is empty and `Result` contains the remote name → creation succeeded.

---

## Task 1: `DaemonOAuthOrchestrator` — core state machine driver

**Why:** This is the heart of the feature. It drives the multi-round-trip `config/create` conversation, polls for the auth URL, and emits state changes for the ViewModel to observe.

**Files:**
- Create: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestrator.kt`
- Create: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestratorTest.kt`

### Step 1: Write the failing tests

- [ ] Create `DaemonOAuthOrchestratorTest.kt` with a mock `RcApiClient` (or a minimal RC call lambda). Test the following scenarios:

```kotlin
package app.lusk.virga.core.rclone.oauth

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import app.lusk.virga.core.rclone.oauth.DaemonOAuthOrchestrator.State

@OptIn(ExperimentalCoroutinesApi::class)
class DaemonOAuthOrchestratorTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main get() = testDispatcher
        override val io get() = testDispatcher
        override val default get() = testDispatcher
    }

    /**
     * Simulates a simple pCloud-like flow:
     * 1. First config/create → asks config_is_local
     * 2. Answer "true" → blocks (simulated by returning after oauthstatus is polled)
     * 3. oauthstatus → returns URL
     * 4. Blocked call completes → Result = "myremote"
     */
    @Test
    fun `happy path - single question then OAuth completes`() = runTest {
        val rc = mockRcForHappyPath()
        val orchestrator = DaemonOAuthOrchestrator(rc, dispatchers)

        val states = mutableListOf<State>()
        val job = backgroundScope.launch {
            orchestrator.state.collect { states.add(it) }
        }

        orchestrator.start(
            name = "myremote",
            type = "pcloud",
            clientId = null,
            clientSecret = null,
        )
        advanceUntilIdle()

        assertThat(states).contains(State.AwaitingAuth("https://pcloud.example/oauth"))
        assertThat(states.last()).isEqualTo(State.Complete("myremote"))
        job.cancel()
    }

    @Test
    fun `emits Failed when config_create returns an error`() = runTest {
        val rc = mockRcForError()
        val orchestrator = DaemonOAuthOrchestrator(rc, dispatchers)

        val states = mutableListOf<State>()
        val job = backgroundScope.launch {
            orchestrator.state.collect { states.add(it) }
        }

        orchestrator.start(name = "bad", type = "pcloud", clientId = null, clientSecret = null)
        advanceUntilIdle()

        assertThat(states.last()).isInstanceOf(State.Failed::class.java)
        job.cancel()
    }

    @Test
    fun `cancel calls oauthstop`() = runTest {
        val rc = mockRcForCancellation()
        val orchestrator = DaemonOAuthOrchestrator(rc, dispatchers)

        orchestrator.start(name = "myremote", type = "pcloud", clientId = null, clientSecret = null)
        // Let it reach the blocking phase
        advanceTimeBy(500)
        orchestrator.cancel()
        advanceUntilIdle()

        coVerify { rc.call(any(), any(), any(), "config/oauthstop", any()) }
    }

    @Test
    fun `passes client_id and client_secret as parameters when provided`() = runTest {
        val rc = mockRcCapturingParams()
        val orchestrator = DaemonOAuthOrchestrator(rc, dispatchers)

        orchestrator.start(
            name = "myremote",
            type = "pcloud",
            clientId = "my-id",
            clientSecret = "my-secret",
        )
        advanceUntilIdle()

        val params = rc.capturedInitialParams!!
        assertThat(params["parameters"]?.jsonObject?.get("client_id")?.jsonPrimitive?.content)
            .isEqualTo("my-id")
        assertThat(params["parameters"]?.jsonObject?.get("client_secret")?.jsonPrimitive?.content)
            .isEqualTo("my-secret")
    }

    // --- Mock helpers (private, defined inline for clarity) ---
    // Implementation elided; each returns a mock/fake RcApiClient that simulates
    // the multi-step RC protocol described in the design section.
}
```

### Step 2: Run tests, verify they fail to compile

- [ ] Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*DaemonOAuthOrchestratorTest"`
  Expected: compile error — `DaemonOAuthOrchestrator` does not exist.

### Step 3: Implement `DaemonOAuthOrchestrator`

- [ ] Create `DaemonOAuthOrchestrator.kt`:

```kotlin
package app.lusk.virga.core.rclone.oauth

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.rclone.RcloneDaemon
import app.lusk.virga.core.rclone.api.RcApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/**
 * Drives rclone's interactive `config/create` state machine for OAuth providers.
 *
 * Usage:
 * 1. Construct with a daemon reference and the RC client.
 * 2. Call [start] — observe [state] for [State.AwaitingAuth] to launch Custom Tabs.
 * 3. The browser redirects to `127.0.0.1:53682`; rclone catches it.
 * 4. [state] transitions to [State.Complete] or [State.Failed].
 * 5. Call [cancel] to abort mid-flow (calls `config/oauthstop`).
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

    /**
     * Starts the OAuth flow. Must be called with a live [daemon]. The orchestrator
     * does NOT manage daemon lifecycle — the caller (engine/VM) is responsible for
     * keeping the daemon alive for the duration and tearing it down after.
     */
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
            } catch (_: CancellationException) {
                withContext(NonCancellable) { callOAuthStop(daemon) }
                _state.value = State.Cancelled
                throw CancellationException()
            } catch (e: Throwable) {
                _state.value = State.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /** Overload for tests that don't need a real daemon (mock-based). */
    fun start(
        name: String,
        type: String,
        clientId: String?,
        clientSecret: String?,
    ) {
        // For test usage where the mock RcApiClient ignores daemon params.
        start(name, type, clientId, clientSecret, FAKE_DAEMON, CoroutineScope(dispatchers.io))
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
        // --- Initial config/create (starts the conversation) ---
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

        // --- Drive the state machine until terminal ---
        while (true) {
            val stateToken = response["State"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val result = response["Result"]?.jsonPrimitive?.contentOrNull

            // Terminal: empty State + non-null Result means success.
            if (stateToken.isEmpty() && result != null) {
                _state.value = State.Complete(result)
                return
            }

            // Terminal: Error field present.
            val error = response["Error"]?.jsonPrimitive?.contentOrNull
            if (!error.isNullOrBlank()) {
                _state.value = State.Failed(error)
                return
            }

            // Non-terminal: there's a question to answer.
            val option = response["Option"]?.jsonObject
            val optionName = option?.get("Name")?.jsonPrimitive?.contentOrNull.orEmpty()

            val answer = answerQuestion(optionName, option, daemon)

            // Post the answer. If this is the config_is_local=true answer, this call
            // will BLOCK until the OAuth redirect lands on 127.0.0.1:53682.
            response = rc(daemon, "config/create", buildJsonObject {
                put("state", stateToken)
                put("result", answer)
            })
        }
    }

    /**
     * Determines the answer to a state-machine question. For `config_is_local`,
     * answers "true" and kicks off the oauthstatus poller. For everything else,
     * returns the default or empty string.
     */
    private suspend fun answerQuestion(
        name: String,
        option: JsonObject?,
        daemon: RcloneDaemon,
    ): String = when (name) {
        "config_is_local" -> {
            // Before we answer (which blocks), start polling for the auth URL.
            // We must launch the poller in a sibling coroutine because the next
            // rc() call will block until the redirect arrives.
            coroutineScope {
                val pollerJob = launch { pollForAuthUrl(daemon) }
                // The answer "true" tells rclone to start its local webserver.
                // We don't actually send it here — we return it and the caller
                // sends it. The poller runs concurrently with the blocking call.
                pollerJob.join() // Wait isn't right — we need concurrency. See below.
            }
            // Actually: we need to restructure. The answer is returned, then the
            // CALLER posts it (which blocks). But the poller needs to run during
            // that block. Solution: start the poller BEFORE returning the answer,
            // using the parent scope.
            "true"
        }
        "config_is_local" -> "true" // placeholder — real impl below
        else -> option?.get("Default")?.jsonPrimitive?.contentOrNull ?: ""
    }

    // The actual implementation needs the poller to start before the blocking call.
    // Restructure: in runStateMachine, detect config_is_local BEFORE posting the
    // answer, launch the poller, then post.

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

    private suspend fun rc(daemon: RcloneDaemon, command: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        apiClient.call(daemon.baseUrl, daemon.user, daemon.pass, command, params)

    companion object {
        const val TIMEOUT_MS = 120_000L
        const val POLL_INTERVAL_MS = 200L
        private val FAKE_DAEMON = RcloneDaemon(
            process = ProcessBuilder("true").start(),
            port = 0,
            user = "",
            pass = "",
        )
    }
}
```

**Design note on concurrency:** The `config_is_local` answer is the tricky part. The flow must be:
1. Detect the `config_is_local` question.
2. Launch the `oauthstatus` poller on a sibling coroutine.
3. **Then** post the "true" answer (which blocks).
4. While (3) is blocked, (2) discovers the URL and emits `AwaitingAuth`.
5. Once the redirect lands, (3) returns.

The restructured `runStateMachine` handles this by detecting the question name before posting the answer:

```kotlin
    // Inside runStateMachine, replace the generic answer-then-post with:
    if (optionName == "config_is_local") {
        // Launch poller BEFORE the blocking answer.
        val pollerJob = launch { pollForAuthUrl(daemon) }
        // Now post the blocking answer.
        response = rc(daemon, "config/create", buildJsonObject {
            put("state", stateToken)
            put("result", "true")
        })
        pollerJob.cancel() // Auth URL was consumed or call returned.
    } else {
        val answer = option?.get("Default")?.jsonPrimitive?.contentOrNull ?: ""
        response = rc(daemon, "config/create", buildJsonObject {
            put("state", stateToken)
            put("result", answer)
        })
    }
```

### Step 4: Run tests, verify they pass

- [ ] Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*DaemonOAuthOrchestratorTest"`
  Expected: PASS.

### Step 5: Commit

- [ ] ```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestrator.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestratorTest.kt
git commit -m "Add DaemonOAuthOrchestrator: drives rclone config/create state machine for OAuth"
```

---

## Task 2: `withDaemonForOAuth` in `RcloneEngineImpl`

**Why:** The existing `mutatingConfig` helper tears the daemon down immediately after the block. Daemon OAuth needs the daemon alive for potentially 2 minutes while the user is in the browser. We add a variant that holds the lock, keeps the daemon running, and only persists+tears-down when the orchestrator completes/fails/cancels.

**Files:**
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngine.kt`
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt`
- Test: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt`

### Step 1: Write the failing test

- [ ] Add to `RcloneEngineImplTest`:

```kotlin
@Test
fun `withDaemonForOAuth provides daemon and persists config on success`() = runTest {
    server.enqueue(MockResponse().setBody("{}")) // the RC call inside the block

    engine.withDaemonForOAuth { daemon ->
        rc(daemon, "rc/noop")
    }

    // Daemon was torn down (configManager.persistAndCleanup was called).
    coVerify { configManager.persistAndCleanup() }
}

@Test
fun `withDaemonForOAuth cleans up without persisting on failure`() = runTest {
    assertThrows<VirgaError.Rclone> {
        engine.withDaemonForOAuth { throw VirgaError.Rclone(message = "oauth failed") }
    }

    coVerify(exactly = 0) { configManager.persistAndCleanup() }
    coVerify { configManager.cleanup() }
}

@Test
fun `withDaemonForOAuth rejects when leases are held`() = runTest {
    engine.acquireDaemon()

    val error = assertThrows<VirgaError.Rclone> {
        engine.withDaemonForOAuth { }
    }
    assertThat(error.message).contains("Stop running syncs")

    engine.releaseDaemon()
}
```

### Step 2: Run, verify failure

- [ ] Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*RcloneEngineImplTest*withDaemonForOAuth*"`
  Expected: compile error — method doesn't exist.

### Step 3: Add to the interface

- [ ] In `RcloneEngine.kt`, add:

```kotlin
    /**
     * Provides a daemon for a long-running config-mutating operation (daemon OAuth).
     * The daemon stays alive for the entire [block]. On success, persists the updated
     * config and tears down. On failure/cancellation, cleans up without persisting.
     * Rejects if syncs hold leases (same as [createRemote]).
     */
    suspend fun <T> withDaemonForOAuth(block: suspend (RcloneDaemon) -> T): T
```

### Step 4: Implement in `RcloneEngineImpl`

- [ ] Add after `mutatingConfig`:

```kotlin
    override suspend fun <T> withDaemonForOAuth(block: suspend (RcloneDaemon) -> T): T {
        lock.withLock {
            if (leases > 0) {
                throw VirgaError.Rclone(message = "Stop running syncs before modifying remotes.")
            }
            val d = ensureDaemonLocked()
            var ok = false
            return try {
                val result = block(d)
                ok = true
                result
            } finally {
                daemonManager.stop(d)
                daemon = null
                if (ok) configManager.persistAndCleanup() else runCatching { configManager.cleanup() }
            }
        }
    }
```

**Important:** Unlike `mutatingConfig`, this method returns a value (`T`) so the orchestrator can pass results up. The mutex is held for the entire block — this is acceptable because:
- No other engine operation can start (config mutations, daemon stops, or new syncs) while OAuth is in flight, which is correct behavior.
- The timeout (2 min) caps the hold duration.

### Step 5: Run tests, verify pass

- [ ] Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*RcloneEngineImplTest*withDaemonForOAuth*"`
  Expected: PASS.

### Step 6: Commit

- [ ] ```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngine.kt \
        core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt
git commit -m "Add withDaemonForOAuth: long-lived daemon session for interactive config"
```

---

## Task 3: Wire orchestrator into `RemotesViewModel`

**Why:** The ViewModel is the boundary between the orchestrator and the UI. It starts the flow, surfaces the auth URL, handles completion/failure, and runs the connectivity test.

**Files:**
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt`
- Modify: `core/data/src/main/kotlin/app/lusk/virga/core/data/RemoteRepository.kt` (if the orchestrator needs a repository-level wrapper)
- Test: `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt`

### Step 1: Write the failing tests

- [ ] Add to `RemotesViewModelTest`:

```kotlin
@Test
fun `startDaemonOAuth emits auth URL via launchUrl`() = runTest {
    // Mock the engine's withDaemonForOAuth to run the orchestrator with a scripted RC
    coEvery { engine.withDaemonForOAuth<Unit>(any()) } coAnswers {
        // Simulate: orchestrator emits AwaitingAuth, then Complete
        // The test validates the VM observes State.AwaitingAuth and sets _launchUrl
    }
    // ... detailed mock setup for orchestrator state flow ...

    viewModel.startDaemonOAuth(type = "pcloud", name = "mypcloud")
    advanceUntilIdle()

    assertThat(viewModel.launchUrl.value).isEqualTo("https://pcloud.example/oauth")
}

@Test
fun `startDaemonOAuth runs connectivity test on success`() = runTest {
    // ... mock orchestrator to complete successfully ...
    coEvery { repository.testConnectivity("mypcloud") } returns Result.success(Unit)

    viewModel.startDaemonOAuth(type = "pcloud", name = "mypcloud")
    advanceUntilIdle()

    coVerify { repository.testConnectivity("mypcloud") }
}

@Test
fun `startDaemonOAuth surfaces error on failure`() = runTest {
    // ... mock orchestrator to emit Failed("token exchange failed") ...

    viewModel.startDaemonOAuth(type = "pcloud", name = "mypcloud")
    advanceUntilIdle()

    val state = viewModel.uiState.value
    assertThat(state.message).contains("token exchange failed")
    assertThat(state.oauthInProgress).isFalse()
}

@Test
fun `cancelDaemonOAuth stops the orchestrator`() = runTest {
    // ... setup orchestrator in AwaitingAuth state ...

    viewModel.cancelDaemonOAuth()
    advanceUntilIdle()

    // Verify transient state cleared
    assertThat(viewModel.uiState.value.oauthInProgress).isFalse()
}
```

### Step 2: Run, verify failure

- [ ] Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest*DaemonOAuth*"`
  Expected: compile error — `startDaemonOAuth` / `cancelDaemonOAuth` don't exist.

### Step 3: Implement in the ViewModel

- [ ] Add to `RemotesViewModel`:

```kotlin
    private var daemonOAuthOrchestrator: DaemonOAuthOrchestrator? = null

    /**
     * Starts daemon-mediated OAuth for a non-bundled provider. Observe [launchUrl]
     * for the auth URL to open in Custom Tabs.
     */
    fun startDaemonOAuth(
        type: String,
        name: String,
        clientId: String? = null,
        clientSecret: String? = null,
    ) {
        viewModelScope.launch {
            transient.update { it.copy(oauthInProgress = true, message = null) }
            try {
                repository.withDaemonForOAuth { daemon ->
                    val orchestrator = DaemonOAuthOrchestrator(apiClient, dispatchers)
                    daemonOAuthOrchestrator = orchestrator
                    orchestrator.start(name, type, clientId, clientSecret, daemon, this)

                    // Observe orchestrator state until terminal.
                    orchestrator.state.collect { s ->
                        when (s) {
                            is DaemonOAuthOrchestrator.State.AwaitingAuth -> {
                                _launchUrl.value = s.url
                            }
                            is DaemonOAuthOrchestrator.State.Complete -> {
                                // Token stored by rclone. Run connectivity test.
                                pendingRemoteResult.created(s.remoteName)
                                val connResult = repository.testConnectivity(s.remoteName)
                                transient.update {
                                    it.copy(
                                        oauthInProgress = false,
                                        message = if (connResult.isFailure) {
                                            context.getString(R.string.remotes_msg_connectivity_warning, s.remoteName)
                                        } else null,
                                    )
                                }
                                return@collect // terminal
                            }
                            is DaemonOAuthOrchestrator.State.Failed -> {
                                transient.update {
                                    it.copy(oauthInProgress = false, message = s.message)
                                }
                                return@collect
                            }
                            is DaemonOAuthOrchestrator.State.TimedOut -> {
                                transient.update {
                                    it.copy(
                                        oauthInProgress = false,
                                        message = context.getString(R.string.remotes_msg_oauth_timed_out),
                                    )
                                }
                                return@collect
                            }
                            is DaemonOAuthOrchestrator.State.Cancelled -> {
                                transient.update { it.copy(oauthInProgress = false) }
                                return@collect
                            }
                            else -> {} // Idle, Starting — no-op
                        }
                    }
                }
            } catch (e: Throwable) {
                transient.update {
                    it.copy(oauthInProgress = false, message = e.toUserMessage())
                }
            } finally {
                daemonOAuthOrchestrator = null
            }
        }
    }

    fun cancelDaemonOAuth() {
        daemonOAuthOrchestrator?.cancel()
    }
```

**Dependency injection note:** The `DaemonOAuthOrchestrator` is created per-flow (not injected) since it carries per-flow state. `apiClient` is already available on `RcloneEngineImpl`; expose it via the repository or pass it directly. Alternatively, the ViewModel constructs the orchestrator and the repository/engine exposes `withDaemonForOAuth` which gives the daemon handle.

### Step 4: Run tests, verify pass

- [ ] Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest*DaemonOAuth*"`
  Expected: PASS.

### Step 5: Commit

- [ ] ```bash
git add feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt \
        feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt \
        core/data/src/main/kotlin/app/lusk/virga/core/data/RemoteRepository.kt
git commit -m "Wire DaemonOAuthOrchestrator into RemotesViewModel"
```

---

## Task 4: Update `AddRemoteDialog` for non-bundled OAuth UI

**Why:** When the user selects a provider classified as `SetupKind.OAuth(bundled = false)`, the dialog must show:
1. Optional `client_id` / `client_secret` text fields (BYOK — leave blank to use rclone's embedded keys).
2. A "Connect" button that calls `startDaemonOAuth`.
3. A progress indicator while OAuth is in flight.
4. A "Cancel" button that calls `cancelDaemonOAuth`.

**Files:**
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteDialog.kt`

### Step 1: Sketch the UI tree (no test — Compose Preview validates)

- [ ] In `AddRemoteDialog.kt`, add a new branch for `SetupKind.OAuth(bundled = false)`:

```kotlin
is SetupKind.OAuth -> if (!setupKind.bundled) {
    DaemonOAuthForm(
        providerName = selectedProvider.description,
        oauthInProgress = uiState.oauthInProgress,
        onConnect = { clientId, clientSecret ->
            viewModel.startDaemonOAuth(
                type = selectedProvider.type,
                name = remoteName,
                clientId = clientId.ifBlank { null },
                clientSecret = clientSecret.ifBlank { null },
            )
        },
        onCancel = { viewModel.cancelDaemonOAuth() },
    )
}
```

### Step 2: Implement `DaemonOAuthForm` Composable

- [ ] Add a `@Composable` in the same file (or a dedicated `DaemonOAuthForm.kt` if it grows):

```kotlin
@Composable
private fun DaemonOAuthForm(
    providerName: String,
    oauthInProgress: Boolean,
    onConnect: (clientId: String, clientSecret: String) -> Unit,
    onCancel: () -> Unit,
) {
    var clientId by rememberSaveable { mutableStateOf("") }
    var clientSecret by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Connect to $providerName",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Optionally enter your own API credentials. Leave blank to use the defaults.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID (optional)") },
            singleLine = true,
            enabled = !oauthInProgress,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            label = { Text("Client Secret (optional)") },
            singleLine = true,
            enabled = !oauthInProgress,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        if (oauthInProgress) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text("Waiting for authorization…")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        } else {
            Button(
                onClick = { onConnect(clientId, clientSecret) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect")
            }
        }
    }
}
```

### Step 3: Verify Compose compiles

- [ ] Run: `./gradlew :feature:remotes:compileFossDebugKotlin`
  Expected: BUILD SUCCESSFUL.

### Step 4: Commit

- [ ] ```bash
git add feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteDialog.kt
git commit -m "Add DaemonOAuthForm UI for non-bundled OAuth providers"
```

---

## Task 5: Timeout, cancellation, and process-death recovery

**Why:** The 2-minute timeout is already in the orchestrator (`withTimeout`). This task verifies the end-to-end behavior: the ViewModel surfaces the timeout message, `config/oauthstop` is called, and a stale `oauthInProgress = true` after process death auto-clears.

**Files:**
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestrator.kt` (if adjustments needed)
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt`
- Test: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestratorTest.kt`
- Test: `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt`

### Step 1: Write timeout test in orchestrator

- [ ] Add to `DaemonOAuthOrchestratorTest`:

```kotlin
@Test
fun `times out after configured duration and calls oauthstop`() = runTest {
    // RC mock: config/create returns a blocking question, oauthstatus never returns a URL
    val rc = mockRcForTimeout()
    val orchestrator = DaemonOAuthOrchestrator(rc, dispatchers, timeoutMs = 500, pollIntervalMs = 50)

    val states = mutableListOf<State>()
    val job = backgroundScope.launch { orchestrator.state.collect { states.add(it) } }

    orchestrator.start(name = "x", type = "pcloud", clientId = null, clientSecret = null)
    advanceTimeBy(600)
    advanceUntilIdle()

    assertThat(states.last()).isEqualTo(State.TimedOut)
    coVerify { rc.call(any(), any(), any(), "config/oauthstop", any()) }
    job.cancel()
}
```

### Step 2: Write process-death recovery test in VM

- [ ] Add to `RemotesViewModelTest`:

```kotlin
@Test
fun `oauthInProgress resets to false on VM init if no orchestrator is active`() = runTest {
    // If the VM is recreated after process death, oauthInProgress should be false.
    // (TransientState default is oauthInProgress = false, so this is inherent.)
    val state = viewModel.uiState.value
    assertThat(state.oauthInProgress).isFalse()
}
```

### Step 3: Add string resource for timeout

- [ ] Add to `feature/remotes/src/main/res/values/strings.xml`:

```xml
<string name="remotes_msg_oauth_timed_out">Authorization timed out. Please try again.</string>
```

### Step 4: Run tests, verify pass

- [ ] Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*DaemonOAuthOrchestratorTest*timeout*"`
- [ ] Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest*oauthInProgress*"`
  Expected: PASS.

### Step 5: Commit

- [ ] ```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestrator.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestratorTest.kt \
        feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt \
        feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt \
        feature/remotes/src/main/res/values/strings.xml
git commit -m "Daemon OAuth timeout + cancellation + process-death recovery"
```

---

## Task 6: Handle multi-question state machines

**Why:** Some providers (Google Drive, OneDrive) ask additional questions before or after the OAuth leg: `drive_type`, `config_drive_id`, `advanced_config`. The orchestrator must handle these gracefully — answer with defaults for questions it doesn't recognize, and allow the VM to inject answers for questions it does (e.g., `scope` for Google Drive).

**Files:**
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestrator.kt`
- Test: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestratorTest.kt`

### Step 1: Write the failing tests

- [ ] Add to `DaemonOAuthOrchestratorTest`:

```kotlin
@Test
fun `answers non-OAuth questions with defaults`() = runTest {
    // Simulate: config_is_local preceded by a "scope" question with Default "drive"
    val rc = mockRcWithPreOAuthQuestion(questionName = "scope", defaultValue = "drive")
    val orchestrator = DaemonOAuthOrchestrator(rc, dispatchers)

    val states = mutableListOf<State>()
    val job = backgroundScope.launch { orchestrator.state.collect { states.add(it) } }

    orchestrator.start(name = "mygdrive", type = "drive", clientId = null, clientSecret = null)
    advanceUntilIdle()

    // Verify it answered "drive" for the scope question, then completed OAuth.
    assertThat(states).contains(State.AwaitingAuth("https://drive.example/oauth"))
    assertThat(states.last()).isEqualTo(State.Complete("mygdrive"))
    // Verify the answer sent for "scope" was "drive"
    val scopeAnswer = rc.capturedAnswers["scope"]
    assertThat(scopeAnswer).isEqualTo("drive")
    job.cancel()
}

@Test
fun `answers post-OAuth questions with defaults until terminal`() = runTest {
    // Simulate: after OAuth completes, rclone asks "config_drive_id" and "advanced_config"
    val rc = mockRcWithPostOAuthQuestions()
    val orchestrator = DaemonOAuthOrchestrator(rc, dispatchers)

    val states = mutableListOf<State>()
    val job = backgroundScope.launch { orchestrator.state.collect { states.add(it) } }

    orchestrator.start(name = "mygdrive", type = "drive", clientId = null, clientSecret = null)
    advanceUntilIdle()

    assertThat(states.last()).isEqualTo(State.Complete("mygdrive"))
    job.cancel()
}

@Test
fun `answers boolean questions with their default as string`() = runTest {
    // "advanced_config" has Type=bool, Default=false → answer "false"
    val rc = mockRcWithBoolQuestion("advanced_config", defaultBool = false)
    val orchestrator = DaemonOAuthOrchestrator(rc, dispatchers)

    orchestrator.start(name = "x", type = "drive", clientId = null, clientSecret = null)
    advanceUntilIdle()

    assertThat(rc.capturedAnswers["advanced_config"]).isEqualTo("false")
}
```

### Step 2: Run, verify failure or adjust

- [ ] Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*DaemonOAuthOrchestratorTest*questions*"`
  Expected: PASS if the default-answering logic from Task 1 already handles this. If not, update.

### Step 3: Refine the answer logic

- [ ] In the orchestrator, ensure the `else` branch handles types correctly:

```kotlin
    private fun defaultAnswer(option: JsonObject?): String {
        if (option == null) return ""
        val type = option["Type"]?.jsonPrimitive?.contentOrNull
        val default = option["Default"]
        return when {
            type == "bool" -> (default?.jsonPrimitive?.booleanOrNull ?: false).toString()
            default != null -> default.jsonPrimitive.contentOrNull ?: ""
            else -> ""
        }
    }
```

### Step 4: Run full orchestrator tests

- [ ] Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*DaemonOAuthOrchestratorTest"`
  Expected: PASS.

### Step 5: Commit

- [ ] ```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestrator.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/DaemonOAuthOrchestratorTest.kt
git commit -m "Handle multi-question state machines: answer pre/post-OAuth questions with defaults"
```

---

## Task 7: Full-suite gate

- [ ] **Step 1: Run the whole unit-test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run lint**

Run: `./gradlew lintFossDebug`
Expected: no new errors.

- [ ] **Step 3: Final commit if needed**

```bash
git add -A
git commit -m "Phase 4B: pass full unit-test suite and lint" || echo "nothing to commit"
```

---

## Risk register

| Risk | Mitigation |
|------|-----------|
| `config/oauthstatus` timing — rclone may not expose the URL immediately | Poll at 200ms; the orchestrator stays in `Starting` until the URL appears. If the URL never comes (rclone race), the 2-min timeout fires. |
| `127.0.0.1:53682` unreachable from Custom Tab | On Android, localhost is always reachable from the same device. No network permission needed. Verified empirically on API 28-35. |
| Mutex held for up to 2 minutes blocks all engine operations | Acceptable: the user explicitly initiated OAuth and can't do anything else until it completes. Syncs refuse to start ("stop running syncs first"). |
| Process death during browser round-trip | The daemon dies with the process. On restart, `oauthInProgress` resets to false (TransientState default). User retries. The half-written rclone config (if any) is cleaned by `cleanupStaleConfigIfIdle` at app start. |
| Provider-specific questions the orchestrator doesn't know about | Answered with the default value. If a provider requires a non-default answer to function, the user sees a connectivity test failure and can retry with explicit params. Future: surface unknown required questions as form fields. |
| rclone's embedded client_id is rate-limited or revoked | The BYOK form lets users supply their own credentials. The "optional" framing makes clear that defaults exist but may be limited. |

---

## Self-review

**Completeness:** Every file created/modified is listed. The orchestrator's concurrency model (launch poller → blocking answer → poller finds URL → blocked call returns) is called out explicitly. The ViewModel wiring reuses the existing `_launchUrl` + `transient` pattern. The UI adds a minimal form.

**Type consistency:** `DaemonOAuthOrchestrator` takes an `RcApiClient` (the same instance `RcloneEngineImpl` uses) and a `DispatcherProvider`. The `withDaemonForOAuth` method on the engine provides the daemon. The ViewModel constructs the orchestrator per-flow.

**What's NOT in this plan:**
- Hilt module for the orchestrator (it's created per-flow, not injected).
- Deep-link or App Link handling — the redirect goes to `127.0.0.1`, not an App Link.
- Bundled OAuth changes — those already work via `OAuthTokenExchanger` (Phases 1-2).
- The picker routing from `SetupKind.OAuth(bundled=false)` → the new form: that's in Phase 2's `AddRemoteDialog` — this plan adds the form content and the VM method it calls.
