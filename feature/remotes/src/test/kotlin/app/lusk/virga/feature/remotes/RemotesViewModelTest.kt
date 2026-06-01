package app.lusk.virga.feature.remotes

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.datastore.OAuthKeyStore
import app.lusk.virga.core.common.model.Remote
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
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class RemotesViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val remotesFlow = MutableStateFlow<List<Remote>>(emptyList())
    private val repository: RemoteRepository = mockk(relaxed = true) {
        every { remotes } returns remotesFlow
    }
    private val tokenExchanger: OAuthTokenExchanger = mockk(relaxed = true)
    private val store = OAuthStore()  // real instance — simple data holder

    // No bring-your-own overrides by default: clientIds is empty, clientId() returns null
    // so startOAuth falls back to the built-in config.
    private val keyStore: OAuthKeyStore = mockk(relaxed = true) {
        every { clientIds } returns MutableStateFlow(emptyMap())
        coEvery { clientId(any()) } returns null
    }

    /**
     * Mock Context that resolves getString calls to predictable values matching
     * the English string templates in strings.xml, without requiring Robolectric.
     */
    private val context: Context = mockk {
        every { getString(any()) } answers {
            val id = firstArg<Int>()
            when (id) {
                R.string.remotes_msg_state_mismatch -> "Sign-in state mismatch; please try again."
                R.string.remotes_msg_could_not_save -> "Could not save remote."
                R.string.remotes_msg_config_imported -> "Config imported."
                R.string.remotes_msg_import_too_large -> "Config file is too large to import (max 256 KB)."
                else -> "string#$id"
            }
        }
        every { getString(any(), *anyVararg()) } answers {
            val id = firstArg<Int>()
            // MockK may record the format varargs either flattened or wrapped in a
            // single Object[] — normalise both shapes to a flat list.
            val args = args.drop(1).flatMap { if (it is Array<*>) it.toList() else listOf(it) }
            when (id) {
                R.string.remotes_msg_oauth_not_configured ->
                    "${args[0]} OAuth client ID isn't configured yet."
                R.string.remotes_msg_sign_in_failed ->
                    "Sign-in failed: ${args[0]}"
                R.string.remotes_msg_added_remote ->
                    "Added ${args[0]} remote \"${args[1]}\""
                else -> "string#$id(${args.joinToString()})"
            }
        }
    }

    private fun config(gdriveClientId: String = "client-google") = OAuthConfig(
        defaultRedirectUri = "virga://oauth/callback",
        clientIds = mapOf(
            OAuthProviders.GoogleDrive.id to gdriveClientId,
            OAuthProviders.OneDrive.id to "",
            OAuthProviders.Dropbox.id to "client-dropbox",
        ),
    )

    // Route the VM's IO work onto the test scheduler so advanceUntilIdle() awaits it.
    private val testDispatchers = object : app.lusk.virga.core.common.dispatchers.DispatcherProvider {
        override val main = mainDispatcher.dispatcher
        override val default = mainDispatcher.dispatcher
        override val io = mainDispatcher.dispatcher
    }

    private fun viewModel(cfg: OAuthConfig = config()) =
        RemotesViewModel(context, repository, cfg, store, tokenExchanger, keyStore, testDispatchers, PendingRemoteResult())

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
        val pendingSlot = slot<OAuthTokenExchanger.PendingAuth>()
        coVerify { tokenExchanger.authorizeUrl(capture(pendingSlot)) }

        // An error whose state matches the in-flight auth tears it down + reports.
        store.emit(OAuthResult.Error(state = pendingSlot.captured.state, message = "user_denied"))
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).contains("user_denied")
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        coVerify(exactly = 0) { repository.addRemote(any(), any(), any()) }
        collector.cancel()
    }

    @Test
    fun oauthError_withForeignState_isIgnored() = runTest(mainDispatcher.dispatcher) {
        every { tokenExchanger.authorizeUrl(any()) } returns "https://x"
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }

        vm.startOAuth(OAuthProviders.GoogleDrive, "g")
        advanceUntilIdle()

        // An error redirect bearing a non-matching state (e.g. injected by another
        // app) must NOT surface a failure for the unrelated in-flight flow.
        store.emit(OAuthResult.Error(state = "not-the-pending-state", message = "user_denied"))
        advanceUntilIdle()

        assertThat(vm.uiState.value.message ?: "").doesNotContain("user_denied")
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

    @Test
    fun addRemote_lowercasesAndTrimsBeckendType() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addRemote(any(), any(), any()) } returns Result.success(Unit)
        val vm = viewModel()

        vm.addRemote(
            name = "my-bucket",
            type = " S3 ",
            paramsText = "",
            onResult = { _, _ -> },
        )
        advanceUntilIdle()

        coVerify {
            repository.addRemote(
                name = "my-bucket",
                type = "s3",
                params = any(),
            )
        }
    }

    // --- importConfigFromUri ---------------------------------------------------

    /**
     * Builds a mock Uri + Context whose ContentResolver returns [bytes] from
     * openInputStream.
     */
    private fun uriReturning(bytes: ByteArray): Uri {
        val stream = ByteArrayInputStream(bytes)
        val resolver: ContentResolver = mockk {
            every { openInputStream(any()) } returns stream
        }
        every { context.contentResolver } returns resolver
        return mockk()
    }

    @Test
    fun `importConfigFromUri emits config-imported message on success`() = runTest(mainDispatcher.dispatcher) {
        val confText = "[myremote]\ntype = drive\n"
        val uri = uriReturning(confText.toByteArray(Charsets.UTF_8))
        coEvery { repository.importConfig(any()) } returns Result.success(Unit)
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.importConfigFromUri(uri)
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).isEqualTo("Config imported.")
        coVerify { repository.importConfig(confText) }
        collector.cancel()
    }

    @Test
    fun `importConfigFromUri emits too-large message when content exceeds 256 KB`() = runTest(mainDispatcher.dispatcher) {
        // 256 * 1024 + 1 bytes — just over the cap
        val oversizedBytes = ByteArray(256 * 1024 + 1) { 'x'.code.toByte() }
        val uri = uriReturning(oversizedBytes)
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.importConfigFromUri(uri)
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).contains("too large")
        coVerify(exactly = 0) { repository.importConfig(any()) }
        collector.cancel()
    }

    @Test
    fun `importConfigFromUri does not call repository when content is exactly at the cap`() = runTest(mainDispatcher.dispatcher) {
        // exactly 256 * 1024 bytes — should succeed (boundary is strictly greater than)
        val exactBytes = ByteArray(256 * 1024) { 'a'.code.toByte() }
        val uri = uriReturning(exactBytes)
        coEvery { repository.importConfig(any()) } returns Result.success(Unit)
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.importConfigFromUri(uri)
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).isEqualTo("Config imported.")
        coVerify(exactly = 1) { repository.importConfig(any()) }
        collector.cancel()
    }

    @Test
    fun `importConfigFromUri surfaces repository failure message`() = runTest(mainDispatcher.dispatcher) {
        val confText = "[remote]\ntype = s3\n"
        val uri = uriReturning(confText.toByteArray(Charsets.UTF_8))
        coEvery { repository.importConfig(any()) } returns Result.failure(RuntimeException("parse error"))
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.importConfigFromUri(uri)
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).isEqualTo("parse error")
        collector.cancel()
    }

    // --- startOAuth with getString vararg pattern ----------------------------

    @Test
    fun `startOAuth missing client id message contains provider display name`() = runTest(mainDispatcher.dispatcher) {
        // OneDrive has an empty client ID in the default config()
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startOAuth(OAuthProviders.OneDrive, "my-onedrive")
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).contains("OneDrive")
        assertThat(vm.uiState.value.message).contains("OAuth client ID")
        collector.cancel()
    }

    @Test
    fun `startOAuth missing client id does not set oauthInProgress`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startOAuth(OAuthProviders.OneDrive, "my-onedrive")
        advanceUntilIdle()

        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        collector.cancel()
    }

    @Test
    fun `startOAuth missing client id does not publish launchUrl`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startOAuth(OAuthProviders.OneDrive, "my-onedrive")
        advanceUntilIdle()

        assertThat(vm.launchUrl.value).isNull()
        collector.cancel()
    }

    @Test
    fun `startOAuth valid client id sets oauthInProgress true`() = runTest(mainDispatcher.dispatcher) {
        every { tokenExchanger.authorizeUrl(any()) } returns "https://auth.example/authorize?state=x"
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startOAuth(OAuthProviders.GoogleDrive, "gdrive-remote")
        advanceUntilIdle()

        assertThat(vm.uiState.value.oauthInProgress).isTrue()
        collector.cancel()
    }

    @Test
    fun `startOAuth valid client id publishes non-null launchUrl`() = runTest(mainDispatcher.dispatcher) {
        every { tokenExchanger.authorizeUrl(any()) } returns "https://auth.example/authorize?state=x"
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startOAuth(OAuthProviders.GoogleDrive, "gdrive-remote")
        advanceUntilIdle()

        assertThat(vm.launchUrl.value).isNotNull()
        collector.cancel()
    }

    // --- addRemote lowercase/trim (additional coverage) ----------------------

    @Test
    fun `addRemote lowercases mixed-case backend type`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addRemote(any(), any(), any()) } returns Result.success(Unit)
        val vm = viewModel()

        vm.addRemote(name = "gdrive", type = "Drive", paramsText = "", onResult = { _, _ -> })
        advanceUntilIdle()

        coVerify { repository.addRemote(name = "gdrive", type = "drive", params = any()) }
    }

    @Test
    fun `addRemote trims whitespace from name and type`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addRemote(any(), any(), any()) } returns Result.success(Unit)
        val vm = viewModel()

        vm.addRemote(name = "  spaces  ", type = "  S3  ", paramsText = "", onResult = { _, _ -> })
        advanceUntilIdle()

        coVerify { repository.addRemote(name = "spaces", type = "s3", params = any()) }
    }

    @Test
    fun `addRemote invokes onResult with success false when repository fails`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addRemote(any(), any(), any()) } returns Result.failure(RuntimeException("conflict"))
        val vm = viewModel()
        var resultSuccess: Boolean? = null
        var resultError: String? = null

        vm.addRemote(name = "bad", type = "ftp", paramsText = "", onResult = { s, e -> resultSuccess = s; resultError = e })
        advanceUntilIdle()

        assertThat(resultSuccess).isFalse()
        assertThat(resultError).isEqualTo("conflict")
    }

    private fun OAuthProvider.unused() = Unit  // silence unused-import warning for OAuthProvider import
}
