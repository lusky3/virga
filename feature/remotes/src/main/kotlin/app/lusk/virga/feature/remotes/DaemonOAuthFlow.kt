package app.lusk.virga.feature.remotes

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.toUserMessage
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.rclone.api.RcApiClient
import app.lusk.virga.core.rclone.oauth.DaemonOAuthOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the daemon-mediated OAuth flow for non-bundled providers on behalf of
 * [RemotesViewModel]. Extracted as a collaborator so the VM stays focused on
 * screen state; the VM's public API ([RemotesViewModel.startDaemonOAuth] etc.)
 * delegates here unchanged.
 *
 * [transient] is the VM's transient UI state, shared (not copied) so the
 * double-start guard in [start] also blocks while a bundled Custom-Tabs OAuth
 * flow has `oauthInProgress` set, exactly as before the extraction.
 */
internal class DaemonOAuthFlow(
    private val scope: CoroutineScope,
    private val repository: RemoteRepository,
    private val apiClient: RcApiClient,
    private val dispatchers: DispatcherProvider,
    private val pendingRemoteResult: PendingRemoteResult,
    private val transient: MutableStateFlow<RemotesTransientState>,
    private val strings: Strings,
    private val onLaunchUrl: (String) -> Unit,
) {

    /** String resolution stays with the VM, which owns the Android Context. */
    interface Strings {
        fun connectivityWarning(remoteName: String): String
        fun oauthTimedOut(): String
        fun addedRemote(remoteName: String): String
        fun enterNameFirst(): String
    }

    private var orchestrator: DaemonOAuthOrchestrator? = null

    /**
     * Test seam: unit tests substitute a scripted orchestrator here because
     * driving the real one needs kotlinx-serialization-json responses, which
     * the feature test compile classpath doesn't expose. Production code
     * always uses this default factory.
     */
    internal var orchestratorFactory: () -> DaemonOAuthOrchestrator = {
        // 600s rather than the orchestrator's 120s default: the paste-token
        // flow has the user run `rclone authorize` on ANOTHER machine and
        // paste the result back, which routinely takes several minutes.
        DaemonOAuthOrchestrator(apiClient, dispatchers, timeoutMs = DAEMON_OAUTH_TIMEOUT_MS)
    }

    /** Starts daemon-mediated OAuth for a non-bundled provider. The auth URL surfaces via [onLaunchUrl]. */
    fun start(
        type: String,
        name: String,
        clientId: String? = null,
        clientSecret: String? = null,
    ) {
        // UI-M2 belt-and-suspenders: a blank name would have the daemon write a
        // nameless remote and the flow dead-end. Bail with a clear message before
        // launching rather than after the user has signed in.
        if (name.isBlank()) {
            transient.update { it.copy(message = strings.enterNameFirst()) }
            return
        }
        // Double-start guard: a second tap while a flow is live must not build a
        // second orchestrator or re-enter withDaemonForOAuth. The flag is flipped
        // synchronously (before the launch) so back-to-back taps can't race it.
        if (transient.value.oauthInProgress) return
        transient.update { it.copy(oauthInProgress = true, message = null) }
        scope.launch {
            try {
                // The engine holds a refcount LEASE (not its exclusive Mutex) for
                // this block, so the minutes-long paste wait no longer stalls other
                // engine ops (quota, listing, a scheduled sync). Still, only drive the
                // orchestrator in here: the terminal state is returned out of the block
                // and ALL follow-up repository work happens afterwards, so connectivity
                // tests the PERSISTED config rather than the daemon's live working copy.
                val terminal = repository.withDaemonForOAuth { daemon ->
                    val orchestrator = orchestratorFactory()
                    this@DaemonOAuthFlow.orchestrator = orchestrator
                    orchestrator.start(name.trim(), type, clientId, clientSecret, daemon, this)
                    // Observe orchestrator state until terminal.
                    orchestrator.state.first { s ->
                        when (s) {
                            // Legacy compat: the current orchestrator never emits this.
                            is DaemonOAuthOrchestrator.State.AwaitingAuth -> {
                                onLaunchUrl(s.url)
                                false // keep collecting
                            }
                            // Not terminal: surface rclone's paste instructions and
                            // keep collecting; submitToken resumes the flow.
                            is DaemonOAuthOrchestrator.State.AwaitingTokenPaste -> {
                                transient.update { t -> t.copy(daemonOAuthTokenPrompt = s.instructions) }
                                false
                            }
                            // Not terminal: surface a required-no-default field prompt;
                            // submitFieldAnswer resumes the flow.
                            is DaemonOAuthOrchestrator.State.AwaitingFieldInput -> {
                                val prompt = DaemonOAuthFieldPrompt(
                                    optionName = s.optionName,
                                    label = s.label,
                                    help = s.help,
                                    examples = s.examples,
                                    isPassword = s.isPassword,
                                )
                                transient.update { t -> t.copy(daemonOAuthFieldPrompt = prompt) }
                                false
                            }
                            is DaemonOAuthOrchestrator.State.Complete,
                            is DaemonOAuthOrchestrator.State.Failed,
                            DaemonOAuthOrchestrator.State.TimedOut,
                            DaemonOAuthOrchestrator.State.Cancelled,
                            -> true // terminal
                            else -> false // Idle, Starting — keep collecting
                        }
                    }
                }
                // Lock released; the engine has persisted the daemon-written
                // config. Repository calls are safe again — and connectivity
                // now tests the PERSISTED config, not the daemon's working copy.
                when (terminal) {
                    is DaemonOAuthOrchestrator.State.Complete -> {
                        pendingRemoteResult.created(terminal.remoteName)
                        val connResult = repository.testConnectivity(terminal.remoteName)
                        runCatching { repository.refresh() }
                        transient.update {
                            it.copy(
                                oauthInProgress = false,
                                daemonOAuthTokenPrompt = null,
                                daemonOAuthFieldPrompt = null,
                                message = if (connResult.isFailure) {
                                    strings.connectivityWarning(terminal.remoteName)
                                } else {
                                    strings.addedRemote(terminal.remoteName)
                                },
                            )
                        }
                    }
                    is DaemonOAuthOrchestrator.State.Failed -> {
                        deletePhantomRemote(name)
                        transient.update {
                            it.copy(
                                oauthInProgress = false,
                                daemonOAuthTokenPrompt = null,
                                daemonOAuthFieldPrompt = null,
                                message = terminal.message,
                            )
                        }
                    }
                    DaemonOAuthOrchestrator.State.TimedOut -> {
                        deletePhantomRemote(name)
                        transient.update {
                            it.copy(
                                oauthInProgress = false,
                                daemonOAuthTokenPrompt = null,
                                daemonOAuthFieldPrompt = null,
                                message = strings.oauthTimedOut(),
                            )
                        }
                    }
                    DaemonOAuthOrchestrator.State.Cancelled -> {
                        deletePhantomRemote(name)
                        transient.update {
                            it.copy(
                                oauthInProgress = false,
                                daemonOAuthTokenPrompt = null,
                                daemonOAuthFieldPrompt = null,
                            )
                        }
                    }
                    else -> Unit // unreachable: first{} only returns terminal states
                }
            } catch (e: Throwable) {
                // Cancellation is normal flow control (e.g. VM scope tear-down), not an
                // error to surface — rethrow so the coroutine machinery unwinds cleanly.
                if (e is kotlinx.coroutines.CancellationException) throw e
                transient.update { it.copy(oauthInProgress = false, message = e.toUserMessage()) }
            } finally {
                transient.update {
                    it.copy(
                        oauthInProgress = false,
                        daemonOAuthTokenPrompt = null,
                        daemonOAuthFieldPrompt = null,
                    )
                }
                orchestrator = null
            }
        }
    }

    /**
     * Forwards the token the user pasted (output of `rclone authorize` run on
     * another machine) to the in-flight daemon OAuth flow and dismisses the
     * prompt. No-op when no flow is awaiting a token.
     */
    fun submitToken(token: String) {
        orchestrator?.submitToken(token)
        transient.update { it.copy(daemonOAuthTokenPrompt = null) }
    }

    /**
     * Forwards the field answer the user supplied to the in-flight daemon OAuth
     * flow and dismisses the prompt. No-op when no flow is awaiting a field answer.
     */
    fun submitFieldAnswer(answer: String) {
        orchestrator?.submitFieldAnswer(answer)
        transient.update { it.copy(daemonOAuthFieldPrompt = null) }
    }

    fun cancel() {
        orchestrator?.cancel()
    }

    /**
     * rclone-M1: the daemon persists the remote to config BEFORE the OAuth question
     * machine finishes, so a non-Complete terminal (Failed / TimedOut / Cancelled)
     * leaves a token-less phantom that shows in the list and fails syncs. Remove it
     * best-effort once the engine lock is released (this MUST run after
     * withDaemonForOAuth returns — calling deleteRemote inside the block re-acquires
     * the non-reentrant Mutex and deadlocks).
     */
    private suspend fun deletePhantomRemote(name: String) {
        runCatching { repository.deleteRemote(name.trim()) }
    }

    private companion object {
        /** Daemon OAuth flow cap — generous because the paste-token flow has the
         *  user run `rclone authorize` on another machine and paste the result. */
        const val DAEMON_OAUTH_TIMEOUT_MS = 600_000L
    }
}
