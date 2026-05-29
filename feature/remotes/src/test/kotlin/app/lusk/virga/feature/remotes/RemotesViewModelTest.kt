package app.lusk.virga.feature.remotes

import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.rclone.oauth.OAuthConfig
import app.lusk.virga.core.rclone.oauth.OAuthProvider
import app.lusk.virga.core.rclone.oauth.OAuthProviders
import app.lusk.virga.core.rclone.oauth.OAuthResult
import app.lusk.virga.core.rclone.oauth.OAuthStore
import app.lusk.virga.core.rclone.oauth.OAuthTokenExchanger
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@OptIn(ExperimentalCoroutinesApi::class)
class RemotesViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val remotesFlow = MutableStateFlow<List<RemoteEntity>>(emptyList())
    private val repository: RemoteRepository = mockk(relaxed = true) {
        every { remotes } returns remotesFlow
    }
    private val tokenExchanger: OAuthTokenExchanger = mockk(relaxed = true)
    private val store = OAuthStore()  // real instance — simple data holder

    private fun config(gdriveClientId: String = "client-google") = OAuthConfig(
        defaultRedirectUri = "virga://oauth/callback",
        clientIds = mapOf(
            OAuthProviders.GoogleDrive.id to gdriveClientId,
            OAuthProviders.OneDrive.id to "",
            OAuthProviders.Dropbox.id to "client-dropbox",
        ),
    )

    private fun viewModel(cfg: OAuthConfig = config()) =
        RemotesViewModel(repository, cfg, store, tokenExchanger)

    // --- Provider list -----------------------------------------------------------

    @Test
    fun oauthProviders_exposesAllBundledProviders() {
        assertThat(viewModel().oauthProviders).isEqualTo(OAuthProviders.All)
    }

    // --- startOAuth happy / error paths -----------------------------------------

    @Test
    fun startOAuth_missingClientId_emitsMessageAndDoesNotLaunch() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel(config().copy(clientIds = mapOf(OAuthProviders.OneDrive.id to "")))
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startOAuth(OAuthProviders.OneDrive, "my-onedrive")
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).contains("OneDrive")
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        assertThat(vm.launchUrl.value).isNull()
        collector.cancel()
    }

    @Test
    fun startOAuth_validClientId_publishesAuthorizeUrlAndPending() = runTest(mainDispatcher.dispatcher) {
        every { tokenExchanger.authorizeUrl(any()) } returns "https://accounts.google.example/auth?state=abc"
        val vm = viewModel()
        // uiState is WhileSubscribed(5s) — must have an active subscriber for
        // upstream emissions to land in .value.
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startOAuth(OAuthProviders.GoogleDrive, "my-google")
        advanceUntilIdle()

        assertThat(vm.launchUrl.value).startsWith("https://accounts.google.example/auth")
        assertThat(vm.uiState.value.oauthInProgress).isTrue()
        // Pending must be present in the store under the state that was generated.
        val pendingSlot = slot<OAuthTokenExchanger.PendingAuth>()
        coVerify { tokenExchanger.authorizeUrl(capture(pendingSlot)) }
        assertThat(store.consume(pendingSlot.captured.state)).isNotNull()
        collector.cancel()
    }

    @Test
    fun onLaunchUrlConsumed_clearsLaunchUrl() = runTest(mainDispatcher.dispatcher) {
        every { tokenExchanger.authorizeUrl(any()) } returns "https://x"
        val vm = viewModel()
        vm.startOAuth(OAuthProviders.GoogleDrive, "g")
        advanceUntilIdle()
        assertThat(vm.launchUrl.value).isNotNull()

        vm.onLaunchUrlConsumed()

        assertThat(vm.launchUrl.value).isNull()
    }

    // --- OAuth callback handling ------------------------------------------------

    @Test
    fun oauthSuccess_exchangesAndAddsRemote() = runTest(mainDispatcher.dispatcher) {
        every { tokenExchanger.authorizeUrl(any()) } returns "https://x"
        coEvery { tokenExchanger.exchange(any(), any()) } returns Result.success("""{"access_token":"a"}""")
        coEvery { tokenExchanger.providerConfigExtras(any(), any()) } returns Result.success(emptyMap())
        coEvery { repository.addRemote(any(), any(), any()) } returns Result.success(Unit)
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }

        vm.startOAuth(OAuthProviders.GoogleDrive, "my-google")
        advanceUntilIdle()
        val pendingSlot = slot<OAuthTokenExchanger.PendingAuth>()
        coVerify { tokenExchanger.authorizeUrl(capture(pendingSlot)) }
        val state = pendingSlot.captured.state

        store.emit(OAuthResult.Success(state = state, code = "auth-code"))
        advanceUntilIdle()

        coVerify {
            repository.addRemote(
                name = "my-google",
                type = "drive",
                params = match { it["token"] == """{"access_token":"a"}""" && it["client_id"] == "client-google" },
            )
        }
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        assertThat(vm.uiState.value.message).contains("Added Google Drive")
        collector.cancel()
    }

    @Test
    fun oauthSuccess_withStateMismatch_surfacesErrorMessage() = runTest(mainDispatcher.dispatcher) {
        every { tokenExchanger.authorizeUrl(any()) } returns "https://x"
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }

        vm.startOAuth(OAuthProviders.GoogleDrive, "g")
        advanceUntilIdle()

        store.emit(OAuthResult.Success(state = "completely-wrong-state", code = "c"))
        advanceUntilIdle()

        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        assertThat(vm.uiState.value.message).contains("state mismatch")
        coVerify(exactly = 0) { repository.addRemote(any(), any(), any()) }
        collector.cancel()
    }

    @Test
    fun oauthError_emitsErrorMessageAndClearsPending() = runTest(mainDispatcher.dispatcher) {
        every { tokenExchanger.authorizeUrl(any()) } returns "https://x"
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }

        vm.startOAuth(OAuthProviders.GoogleDrive, "g")
        advanceUntilIdle()

        store.emit(OAuthResult.Error(state = null, message = "user_denied"))
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).contains("user_denied")
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        coVerify(exactly = 0) { repository.addRemote(any(), any(), any()) }
        collector.cancel()
    }

    // --- Misc CRUD --------------------------------------------------------------

    @Test
    fun addRemote_parsesParamsTextAndDelegates() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addRemote(any(), any(), any()) } returns Result.success(Unit)
        val vm = viewModel()
        var doneCalled = false

        vm.addRemote(
            name = "  box-corp ",
            type = "  webdav",
            paramsText = """
                url=https://example.com
                user=alice
                # comment line — no equals so it's ignored
                vendor=other
            """.trimIndent(),
            onResult = { success, _ -> doneCalled = success },
        )
        advanceUntilIdle()

        coVerify {
            repository.addRemote(
                name = "box-corp",
                type = "webdav",
                params = match {
                    it["url"] == "https://example.com" && it["user"] == "alice" && it["vendor"] == "other" && it.size == 3
                },
            )
        }
        assertThat(doneCalled).isTrue()
    }

    @Test
    fun clearMessage_removesPendingMessage() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.refresh() } returns Result.failure(RuntimeException("boom"))
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        vm.refresh()
        advanceUntilIdle()
        assertThat(vm.uiState.value.message).isEqualTo("boom")

        vm.clearMessage()
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).isNull()
        collector.cancel()
    }

    private fun OAuthProvider.unused() = Unit  // silence unused-import warning for OAuthProvider import
}
