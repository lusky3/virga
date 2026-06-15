package app.lusk.virga.feature.remotes

import android.content.Context
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.datastore.OAuthKeyStore
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.rclone.RcloneDaemon
import app.lusk.virga.core.rclone.oauth.DaemonOAuthOrchestrator
import app.lusk.virga.core.rclone.oauth.OAuthConfig
import app.lusk.virga.core.rclone.oauth.OAuthProviders
import app.lusk.virga.core.rclone.oauth.OAuthStore
import app.lusk.virga.core.rclone.oauth.OAuthTokenExchanger
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Tests for the daemon-OAuth required-field prompt (A8):
 * [RemotesViewModel.submitDaemonOAuthFieldAnswer] and the [DaemonOAuthFieldPrompt]
 * surfacing via [RemotesViewModel.uiState]. Kept in a separate file so the parent
 * RemotesViewModelTest stays under the Codacy file-size limit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DaemonOAuthFieldViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val remotesFlow = MutableStateFlow<List<Remote>>(emptyList())
    private val repository: RemoteRepository = mockk(relaxed = true) {
        every { remotes } returns remotesFlow
    }
    private val store = OAuthStore()
    private val keyStore: OAuthKeyStore = mockk(relaxed = true) {
        every { clientIds } returns MutableStateFlow(emptyMap())
        coEvery { clientId(any()) } returns null
    }
    private val tokenExchanger: OAuthTokenExchanger = mockk(relaxed = true)
    private val apiClient: app.lusk.virga.core.rclone.api.RcApiClient = mockk(relaxed = true)

    private val context: Context = mockk {
        every { getString(any()) } answers { "string#${firstArg<Int>()}" }
        every { getString(any(), *anyVararg()) } answers {
            val id = firstArg<Int>()
            val rawArgs = args.drop(1).flatMap { if (it is Array<*>) it.toList() else listOf(it) }
            "string#$id(${rawArgs.joinToString()})"
        }
    }

    private val testDispatchers = object : app.lusk.virga.core.common.dispatchers.DispatcherProvider {
        override val main = mainDispatcher.dispatcher
        override val default = mainDispatcher.dispatcher
        override val io = mainDispatcher.dispatcher
    }

    private fun config() = OAuthConfig(
        defaultRedirectUri = "virga://oauth/callback",
        clientIds = mapOf(
            OAuthProviders.GoogleDrive.id to "client-google",
            OAuthProviders.OneDrive.id to "",
            OAuthProviders.Dropbox.id to "client-dropbox",
        ),
    )

    private fun viewModel() =
        RemotesViewModel(
            context, repository, config(), store, tokenExchanger, keyStore, apiClient,
            testDispatchers, PendingRemoteResult(),
        )

    private fun scriptedOrchestrator(
        states: MutableStateFlow<DaemonOAuthOrchestrator.State>,
    ): DaemonOAuthOrchestrator = mockk(relaxUnitFun = true) {
        every { state } returns states
    }

    private fun executeDaemonBlock() {
        coEvery { repository.withDaemonForOAuth<Any?>(any()) } coAnswers {
            firstArg<suspend (RcloneDaemon) -> Any?>()(mockk(relaxed = true))
        }
    }

    @Test
    fun `AwaitingFieldInput surfaces daemonOAuthFieldPrompt in uiState`() = runTest(mainDispatcher.dispatcher) {
        val states = MutableStateFlow<DaemonOAuthOrchestrator.State>(DaemonOAuthOrchestrator.State.Starting)
        val orchestrator = scriptedOrchestrator(states)
        executeDaemonBlock()
        val vm = viewModel()
        vm.daemonOAuthOrchestratorFactory = { orchestrator }
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "s3", name = "mys3")
        runCurrent()
        states.value = DaemonOAuthOrchestrator.State.AwaitingFieldInput(
            optionName = "access_key_id",
            label = "access_key_id",
            help = "AWS Access Key ID",
            examples = listOf("AKIAIOSFODNN7EXAMPLE"),
            isPassword = false,
        )
        runCurrent()

        val fieldPrompt = vm.uiState.value.daemonOAuthFieldPrompt
        assertThat(fieldPrompt).isNotNull()
        assertThat(fieldPrompt!!.optionName).isEqualTo("access_key_id")
        assertThat(fieldPrompt.help).isEqualTo("AWS Access Key ID")
        assertThat(fieldPrompt.examples).containsExactly("AKIAIOSFODNN7EXAMPLE")
        assertThat(vm.uiState.value.oauthInProgress).isTrue()
        collector.cancel()
    }

    @Test
    fun `submitDaemonOAuthFieldAnswer forwards to orchestrator and clears prompt`() = runTest(mainDispatcher.dispatcher) {
        val states = MutableStateFlow<DaemonOAuthOrchestrator.State>(DaemonOAuthOrchestrator.State.Starting)
        val orchestrator = scriptedOrchestrator(states)
        every { orchestrator.submitFieldAnswer(any()) } answers {
            states.value = DaemonOAuthOrchestrator.State.Complete("mys3")
        }
        executeDaemonBlock()
        coEvery { repository.testConnectivity(any()) } returns Result.success(Unit)
        coEvery { repository.refresh() } returns Result.success(Unit)
        val vm = viewModel()
        vm.daemonOAuthOrchestratorFactory = { orchestrator }
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "s3", name = "mys3")
        runCurrent()
        states.value = DaemonOAuthOrchestrator.State.AwaitingFieldInput(
            optionName = "access_key_id",
            label = "access_key_id",
            help = "AWS Access Key ID",
        )
        runCurrent()
        assertThat(vm.uiState.value.daemonOAuthFieldPrompt).isNotNull()

        vm.submitDaemonOAuthFieldAnswer("AKIAIOSFODNN7EXAMPLE")
        advanceUntilIdle()

        verify { orchestrator.submitFieldAnswer("AKIAIOSFODNN7EXAMPLE") }
        assertThat(vm.uiState.value.daemonOAuthFieldPrompt).isNull()
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        collector.cancel()
    }

    @Test
    fun `daemon oauth failure clears field prompt`() = runTest(mainDispatcher.dispatcher) {
        val states = MutableStateFlow<DaemonOAuthOrchestrator.State>(
            DaemonOAuthOrchestrator.State.AwaitingFieldInput(
                optionName = "access_key_id",
                label = "access_key_id",
                help = "AWS Access Key ID",
            ),
        )
        executeDaemonBlock()
        val vm = viewModel()
        vm.daemonOAuthOrchestratorFactory = { scriptedOrchestrator(states) }
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "s3", name = "mys3")
        runCurrent()
        assertThat(vm.uiState.value.daemonOAuthFieldPrompt).isNotNull()

        states.value = DaemonOAuthOrchestrator.State.Failed("missing key")
        advanceUntilIdle()

        assertThat(vm.uiState.value.daemonOAuthFieldPrompt).isNull()
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        collector.cancel()
    }
}
