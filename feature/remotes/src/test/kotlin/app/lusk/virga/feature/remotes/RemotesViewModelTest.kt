package app.lusk.virga.feature.remotes

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.datastore.OAuthKeyStore
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteOption
import app.lusk.virga.core.common.model.RemoteProvider
import app.lusk.virga.core.common.model.RemoteQuota
import app.lusk.virga.core.rclone.oauth.DaemonOAuthOrchestrator
import app.lusk.virga.core.rclone.oauth.OAuthConfig
import app.lusk.virga.core.rclone.oauth.OAuthProvider
import app.lusk.virga.core.rclone.oauth.OAuthProviders
import app.lusk.virga.core.rclone.oauth.OAuthResult
import app.lusk.virga.core.rclone.oauth.OAuthStore
import app.lusk.virga.core.rclone.oauth.OAuthTokenExchanger
import app.lusk.virga.core.rclone.RcloneDaemon
import app.lusk.virga.core.rclone.SetupKind
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
                R.string.remotes_msg_freeform_no_obscure ->
                    "Heads up: the provider schema wasn't available, so any password values were stored " +
                        "without obscuring. Re-create this remote once the schema loads if it contains secrets."
                R.string.remotes_rename_error_blank -> "Name must not be blank."
                R.string.remotes_rename_error_same -> "New name is the same as the current name."
                R.string.remotes_add_name_invalid -> "Name can't contain ':' or '/'"
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
                R.string.remotes_msg_connectivity_warning ->
                    "Remote \"${args[0]}\" was saved, but could not verify connectivity. Check your credentials."
                R.string.remotes_msg_remote_updated ->
                    "Remote \"${args[0]}\" updated."
                R.string.remotes_rename_error_taken ->
                    "\"${args[0]}\" is already used by another remote."
                R.string.remotes_msg_remote_renamed ->
                    "Renamed \"${args[0]}\" to \"${args[1]}\"."
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

    private val apiClient: app.lusk.virga.core.rclone.api.RcApiClient = mockk(relaxed = true)

    // Route the VM's IO work onto the test scheduler so advanceUntilIdle() awaits it.
    private val testDispatchers = object : app.lusk.virga.core.common.dispatchers.DispatcherProvider {
        override val main = mainDispatcher.dispatcher
        override val default = mainDispatcher.dispatcher
        override val io = mainDispatcher.dispatcher
    }

    private fun viewModel(cfg: OAuthConfig = config()) =
        RemotesViewModel(context, repository, cfg, store, tokenExchanger, keyStore, apiClient, testDispatchers, PendingRemoteResult())

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
    fun `startOAuth with blank remote name does not launch`() = runTest(mainDispatcher.dispatcher) {
        // UI-M2: a blank name would dead-end after sign-in at the state-mismatch
        // check. Bail before launching.
        every { tokenExchanger.authorizeUrl(any()) } returns "https://x"
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startOAuth(OAuthProviders.GoogleDrive, "   ")
        advanceUntilIdle()

        assertThat(vm.launchUrl.value).isNull()
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        assertThat(vm.uiState.value.message).isNotNull()
        verify(exactly = 0) { tokenExchanger.authorizeUrl(any()) }
        collector.cancel()
    }

    @Test
    fun `startOAuth with BYO Google client id refuses to launch`() = runTest(mainDispatcher.dispatcher) {
        // UI-M1: a BYO Google client derives a redirect scheme the manifest doesn't
        // advertise, so the redirect would route nowhere. Refuse rather than dead-end.
        coEvery { keyStore.clientId(OAuthProviders.GoogleDrive.id) } returns
            "999-custom.apps.googleusercontent.com"
        every { tokenExchanger.authorizeUrl(any()) } returns "https://x"
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startOAuth(OAuthProviders.GoogleDrive, "my-google")
        advanceUntilIdle()

        assertThat(vm.launchUrl.value).isNull()
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        assertThat(vm.uiState.value.message).isNotNull()
        verify(exactly = 0) { tokenExchanger.authorizeUrl(any()) }
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
    fun `VM construction with a pre-emitted OAuth result does not throw and handles it`() =
        runTest(mainDispatcher.dispatcher) {
            // UI-H1 regression: OAuthStore retains the last result across VM teardown
            // (the redirect-while-backgrounded case). On Dispatchers.Main.immediate the
            // init collector runs synchronously inside the constructor and the retained
            // value is delivered at once — so if the collector is declared before
            // systemOAuth, it touches a not-yet-initialized field and NPEs. Pre-emit a
            // result, THEN construct, and assert construction succeeds + the result is
            // handled (no pending → state-mismatch) and cleared.
            store.emit(OAuthResult.Success(state = "stale-state", code = "stale-code"))

            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            assertThat(vm.uiState.value.message).contains("state mismatch")
            assertThat(vm.uiState.value.oauthInProgress).isFalse()
            // The collector consumed the retained result so it isn't reprocessed.
            assertThat(store.results.value).isNull()
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

    // --- providers / optionsForBackend ------------------------------------------

    @Test
    fun `ensureProvidersLoaded stores result and optionsForBackend returns filtered options`() = runTest(mainDispatcher.dispatcher) {
        val clientIdOpt = RemoteOption(
            name = "client_id", help = "Your client ID", type = "string",
            required = false, isPassword = false, default = null,
            examples = emptyList(), advanced = false,
        )
        val secretOpt = RemoteOption(
            name = "client_secret", help = "Your client secret", type = "string",
            required = false, isPassword = true, default = null,
            examples = emptyList(), advanced = false,
        )
        val advancedOpt = RemoteOption(
            name = "root_folder_id", help = "Root folder ID", type = "string",
            required = false, isPassword = false, default = null,
            examples = emptyList(), advanced = true,
        )
        val driveProvider = RemoteProvider(
            name = "drive",
            description = "Google Drive",
            options = listOf(clientIdOpt, secretOpt, advancedOpt),
        )
        coEvery { repository.providers() } returns listOf(driveProvider)
        val vm = viewModel()

        // Before loading, optionsForBackend returns null (schema not yet loaded).
        assertThat(vm.optionsForBackend("drive")).isNull()

        vm.ensureProvidersLoaded()
        advanceUntilIdle()

        // After loading, non-advanced options are returned.
        val opts = vm.optionsForBackend("drive")
        assertThat(opts).isNotNull()
        assertThat(opts!!).hasSize(2)
        assertThat(opts.map { it.name }).containsExactly("client_id", "client_secret")
        // Advanced option is excluded from the non-advanced list.
        assertThat(opts.any { it.advanced }).isFalse()
    }

    @Test
    fun `optionsForBackend returns null for unknown backend type`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.providers() } returns listOf(
            RemoteProvider("drive", "Google Drive", emptyList()),
        )
        val vm = viewModel()
        vm.ensureProvidersLoaded()
        advanceUntilIdle()

        assertThat(vm.optionsForBackend("ftp")).isNull()
    }

    @Test
    fun `allOptionsForBackend returns advanced options too`() = runTest(mainDispatcher.dispatcher) {
        val basicOpt = RemoteOption(
            name = "client_id", help = "ID", type = "string",
            required = false, isPassword = false, default = null,
            examples = emptyList(), advanced = false,
        )
        val advancedOpt = RemoteOption(
            name = "root_folder_id", help = "Root folder", type = "string",
            required = false, isPassword = false, default = null,
            examples = emptyList(), advanced = true,
        )
        coEvery { repository.providers() } returns listOf(
            RemoteProvider("drive", "Google Drive", listOf(basicOpt, advancedOpt)),
        )
        val vm = viewModel()
        vm.ensureProvidersLoaded()
        advanceUntilIdle()

        val all = vm.allOptionsForBackend("drive")
        assertThat(all).isNotNull()
        assertThat(all!!).hasSize(2)
        assertThat(all.map { it.name }).containsExactly("client_id", "root_folder_id")

        // optionsForBackend still excludes advanced
        val filtered = vm.optionsForBackend("drive")
        assertThat(filtered).hasSize(1)
        assertThat(filtered!!.first().name).isEqualTo("client_id")
    }

    @Test
    fun `ensureProvidersLoaded is idempotent - does not re-fetch if already loaded`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.providers() } returns emptyList()
        val vm = viewModel()
        vm.ensureProvidersLoaded()
        advanceUntilIdle()
        vm.ensureProvidersLoaded()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.providers() }
    }

    @Test
    fun `optionsForBackend returns null when providers call returned empty list (failure)`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.providers() } returns emptyList()
        val vm = viewModel()
        vm.ensureProvidersLoaded()
        advanceUntilIdle()

        // Empty list signals a failure — fall back to freeform.
        assertThat(vm.optionsForBackend("drive")).isNull()
    }

    // --- createCrypt -----------------------------------------------------------

    @Test
    fun `createCrypt builds baseRemoteSpec with path and delegates to repository`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addCryptRemote(any(), any(), any(), any()) } returns Result.success(Unit)
        val vm = viewModel()

        vm.createCrypt(
            name = "myenc",
            baseRemote = "gdrive",
            basePath = "vault/sub",
            password = "s3cr3t",
            salt = "",
            onResult = { _, _ -> },
        )
        advanceUntilIdle()

        coVerify {
            repository.addCryptRemote(
                name = "myenc",
                baseRemoteSpec = "gdrive:vault/sub",
                password = "s3cr3t",
                salt = null,
            )
        }
    }

    @Test
    fun `createCrypt uses base remote colon only when basePath is blank`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addCryptRemote(any(), any(), any(), any()) } returns Result.success(Unit)
        val vm = viewModel()

        vm.createCrypt("enc2", "s3bucket", "   ", "pw", "", onResult = { _, _ -> })
        advanceUntilIdle()

        coVerify {
            repository.addCryptRemote(
                name = "enc2",
                baseRemoteSpec = "s3bucket:",
                password = "pw",
                salt = null,
            )
        }
    }

    @Test
    fun `createCrypt strips leading slash from basePath`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addCryptRemote(any(), any(), any(), any()) } returns Result.success(Unit)
        val vm = viewModel()

        vm.createCrypt("enc3", "dropbox", "/myfolder", "pw", "", onResult = { _, _ -> })
        advanceUntilIdle()

        coVerify {
            repository.addCryptRemote(
                name = "enc3",
                baseRemoteSpec = "dropbox:myfolder",
                password = "pw",
                salt = null,
            )
        }
    }

    @Test
    fun `createCrypt passes salt to repository when non-blank`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addCryptRemote(any(), any(), any(), any()) } returns Result.success(Unit)
        val vm = viewModel()

        vm.createCrypt("enc4", "b2", "bucket", "pw", "mysalt", onResult = { _, _ -> })
        advanceUntilIdle()

        coVerify {
            repository.addCryptRemote(
                name = "enc4",
                baseRemoteSpec = "b2:bucket",
                password = "pw",
                salt = "mysalt",
            )
        }
    }

    @Test
    fun `createCrypt reports success via onResult`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addCryptRemote(any(), any(), any(), any()) } returns Result.success(Unit)
        val vm = viewModel()
        var didSucceed: Boolean? = null

        vm.createCrypt("enc5", "gdrive", "", "pw", "", onResult = { success, _ -> didSucceed = success })
        advanceUntilIdle()

        assertThat(didSucceed).isTrue()
    }

    @Test
    fun `createCrypt reports failure via onResult when repository fails`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addCryptRemote(any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("bad remote"))
        val vm = viewModel()
        var errMsg: String? = null

        vm.createCrypt("enc6", "gdrive", "", "pw", "", onResult = { _, err -> errMsg = err })
        advanceUntilIdle()

        assertThat(errMsg).isEqualTo("bad remote")
    }

    // --- Quota loading / refresh ------------------------------------------------

    @Test
    fun `fetchQuota shows loading while in flight then clears with quota on success`() =
        runTest(mainDispatcher.dispatcher) {
            val gate = CompletableDeferred<Result<RemoteQuota>>()
            coEvery { repository.about("g") } coAnswers { gate.await() }
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.fetchQuota("g")
            // runCurrent (not advanceUntilIdle) so virtual time doesn't jump past
            // the fetch's timeout window while the gated about() call is suspended.
            runCurrent()

            // In flight: the remote is marked loading and no quota is shown yet.
            assertThat(vm.uiState.value.quotaLoading).contains("g")
            assertThat(vm.uiState.value.quotas).doesNotContainKey("g")

            gate.complete(Result.success(RemoteQuota(total = 100, used = 40, free = 60)))
            advanceUntilIdle()

            assertThat(vm.uiState.value.quotaLoading).doesNotContain("g")
            assertThat(vm.uiState.value.quotas["g"]).isEqualTo(RemoteQuota(100, 40, 60))
            collector.cancel()
        }

    @Test
    fun `fetchQuota failure clears loading and stores no quota`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.about("g") } returns Result.failure(RuntimeException("offline"))
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.fetchQuota("g")
        advanceUntilIdle()

        assertThat(vm.uiState.value.quotaLoading).doesNotContain("g")
        assertThat(vm.uiState.value.quotas).doesNotContainKey("g")
        collector.cancel()
    }

    @Test
    fun `fetchQuota is deduped until refresh re-enables and re-fetches`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.about("g") } returns Result.success(RemoteQuota(100, 40, 60))
        coEvery { repository.refresh() } returns Result.success(Unit)
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.fetchQuota("g"); advanceUntilIdle()
        vm.fetchQuota("g"); advanceUntilIdle()
        coVerify(exactly = 1) { repository.about("g") }

        // A pull-to-refresh bumps the epoch and clears prior quota, so cards re-fetch.
        val epochBefore = vm.uiState.value.quotaEpoch
        vm.refresh(); advanceUntilIdle()
        assertThat(vm.uiState.value.quotaEpoch).isEqualTo(epochBefore + 1)
        assertThat(vm.uiState.value.quotas).doesNotContainKey("g")

        vm.fetchQuota("g"); advanceUntilIdle()
        coVerify(exactly = 2) { repository.about("g") }
        collector.cancel()
    }

    // --- testConnectivity (on-demand, re-runnable) ------------------------------

    @Test
    fun `testConnectivity shows testing while in flight then succeeds`() =
        runTest(mainDispatcher.dispatcher) {
            val gate = CompletableDeferred<Result<Unit>>()
            coEvery { repository.testConnectivity("g") } coAnswers { gate.await() }
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.testConnectivity("g")
            runCurrent()

            // In flight: remote is in the testing set, no result yet.
            assertThat(vm.uiState.value.connectivityTesting).contains("g")
            assertThat(vm.uiState.value.connectivityResults).doesNotContainKey("g")

            gate.complete(Result.success(Unit))
            advanceUntilIdle()

            assertThat(vm.uiState.value.connectivityTesting).doesNotContain("g")
            assertThat(vm.uiState.value.connectivityResults["g"]).isEqualTo(ConnectivityResult.SUCCESS)
            collector.cancel()
        }

    @Test
    fun `testConnectivity failure stores FAILURE result and clears testing`() =
        runTest(mainDispatcher.dispatcher) {
            coEvery { repository.testConnectivity("g") } returns Result.failure(RuntimeException("offline"))
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.testConnectivity("g")
            advanceUntilIdle()

            assertThat(vm.uiState.value.connectivityTesting).doesNotContain("g")
            assertThat(vm.uiState.value.connectivityResults["g"]).isEqualTo(ConnectivityResult.FAILURE)
            collector.cancel()
        }

    @Test
    fun `testConnectivity maps a thrown repository call to FAILURE and clears testing`() =
        runTest(mainDispatcher.dispatcher) {
            // A throwing (not Result.failure) call must still clear `testing`, else the
            // remote would be stuck and the in-flight guard would block every re-test.
            coEvery { repository.testConnectivity("g") } throws RuntimeException("boom")
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.testConnectivity("g")
            advanceUntilIdle()

            assertThat(vm.uiState.value.connectivityTesting).doesNotContain("g")
            assertThat(vm.uiState.value.connectivityResults["g"]).isEqualTo(ConnectivityResult.FAILURE)

            // Re-testable: a second tap actually re-invokes the repository.
            coEvery { repository.testConnectivity("g") } returns Result.success(Unit)
            vm.testConnectivity("g")
            advanceUntilIdle()
            assertThat(vm.uiState.value.connectivityResults["g"]).isEqualTo(ConnectivityResult.SUCCESS)
            coVerify(exactly = 2) { repository.testConnectivity("g") }
            collector.cancel()
        }

    @Test
    fun `testConnectivity concurrent tap is deduped but re-runnable after completion`() =
        runTest(mainDispatcher.dispatcher) {
            val gate = CompletableDeferred<Result<Unit>>()
            coEvery { repository.testConnectivity("g") } coAnswers { gate.await() }
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            // Two taps while in-flight → only one call to the repository.
            vm.testConnectivity("g")
            runCurrent()
            vm.testConnectivity("g")
            runCurrent()
            coVerify(exactly = 1) { repository.testConnectivity("g") }

            // Complete the gate and wait for the coroutine to finish.
            gate.complete(Result.success(Unit))
            advanceUntilIdle()

            // A tap AFTER completion must re-run — the guard must not be permanent.
            vm.testConnectivity("g")
            advanceUntilIdle()
            coVerify(exactly = 2) { repository.testConnectivity("g") }
            collector.cancel()
        }

    @Test
    fun `testConnectivity timeout stores FAILURE result and clears testing`() =
        runTest(mainDispatcher.dispatcher) {
            // The repository call never completes; virtual time advances past the
            // CONNECTIVITY_TIMEOUT_MS (12_000 ms) window so withTimeoutOrNull fires.
            coEvery { repository.testConnectivity("g") } coAnswers {
                awaitCancellation()
            }
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.testConnectivity("g")
            // Jump past the 12-second timeout using virtual time (instant in tests).
            advanceTimeBy(13_000)
            advanceUntilIdle()

            assertThat(vm.uiState.value.connectivityTesting).doesNotContain("g")
            assertThat(vm.uiState.value.connectivityResults["g"]).isEqualTo(ConnectivityResult.FAILURE)
            collector.cancel()
        }

    private fun OAuthProvider.unused() = Unit  // silence unused-import warning for OAuthProvider import

    // --- edit flow ---

    @Test fun `beginEditRemote loads params and sets editMode`() = runTest(mainDispatcher.dispatcher) {
        val params = mapOf("type" to "drive", "client_id" to "abc", "token" to "tok")
        coEvery { repository.getRemoteParams("gdrive") } returns Result.success(params)
        coEvery { repository.providers() } returns listOf(
            RemoteProvider(
                "drive", "Google Drive",
                listOf(
                    RemoteOption("client_id", "Client ID", "string", false, false, null, emptyList(), false),
                    RemoteOption("token", "Token", "string", false, false, null, emptyList(), false),
                ),
            ),
        )

        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        vm.beginEditRemote("gdrive")
        advanceUntilIdle()

        val editMode = vm.uiState.value.editMode
        assertThat(editMode).isNotNull()
        assertThat(editMode!!.remoteName).isEqualTo("gdrive")
        assertThat(editMode.remoteType).isEqualTo("drive")
        assertThat(editMode.loadedParams).containsEntry("client_id", "abc")
        collector.cancel()
    }

    @Test fun `beginEditRemote sets message on failure`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.providers() } returns emptyList()
        coEvery { repository.getRemoteParams("ghost") } returns Result.failure(
            app.lusk.virga.core.common.error.VirgaError.Rclone(message = "not found")
        )

        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        vm.beginEditRemote("ghost")
        advanceUntilIdle()

        assertThat(vm.uiState.value.editMode).isNull()
        assertThat(vm.uiState.value.message).isNotNull()
        collector.cancel()
    }

    @Test fun `submitEdit sends only changed keys to updateRemote`() = runTest(mainDispatcher.dispatcher) {
        val loaded = mapOf("type" to "sftp", "host" to "example.com", "user" to "alice")
        coEvery { repository.providers() } returns listOf(
            RemoteProvider(
                "sftp", "SFTP",
                listOf(
                    RemoteOption("host", "Host", "string", false, false, null, emptyList(), false),
                    RemoteOption("user", "User", "string", false, false, null, emptyList(), false),
                ),
            ),
        )
        coEvery { repository.getRemoteParams("sftp1") } returns Result.success(loaded)
        coEvery { repository.updateRemote(any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.refresh() } returns Result.success(Unit)

        val vm = viewModel()
        vm.beginEditRemote("sftp1")
        advanceUntilIdle()

        var resultSuccess = false
        vm.submitEdit("sftp1", mapOf("host" to "example.com", "user" to "bob")) { success, _ ->
            resultSuccess = success
        }
        advanceUntilIdle()

        assertThat(resultSuccess).isTrue()
        // Only "user" changed ("host" is unchanged).
        coVerify { repository.updateRemote("sftp1", mapOf("user" to "bob"), emptySet()) }
    }

    @Test fun `submitEdit skips blank password fields and sends new password value`() = runTest(mainDispatcher.dispatcher) {
        val loaded = mapOf("type" to "sftp", "host" to "h", "pass" to "obscured_value")
        val options = listOf(
            RemoteOption("host", "Host", "string", false, false, null, emptyList(), false),
            RemoteOption("pass", "Password", "string", false, true, null, emptyList(), false),
        )
        coEvery { repository.getRemoteParams("sftp1") } returns Result.success(loaded)
        coEvery { repository.providers() } returns listOf(
            RemoteProvider("sftp", "SFTP", options)
        )
        coEvery { repository.updateRemote(any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.refresh() } returns Result.success(Unit)

        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        vm.ensureProvidersLoaded()
        advanceUntilIdle()
        vm.beginEditRemote("sftp1")
        advanceUntilIdle()

        // host unchanged, pass blank (keep current) → nothing changed, so updateRemote
        // must NOT be called (no needless exclusive-lock acquisition).
        vm.submitEdit("sftp1", mapOf("host" to "h", "pass" to "")) { _, _ -> }
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.updateRemote(any(), emptyMap(), any()) }

        // First submitEdit cleared editMode — re-enter edit session before second submit.
        vm.beginEditRemote("sftp1")
        advanceUntilIdle()

        // Now set a new password
        vm.submitEdit("sftp1", mapOf("host" to "h", "pass" to "newpass")) { _, _ -> }
        advanceUntilIdle()
        coVerify { repository.updateRemote("sftp1", mapOf("pass" to "newpass"), setOf("pass")) }
        collector.cancel()
    }

    @Test fun `dismissEditRemote clears editMode`() = runTest(mainDispatcher.dispatcher) {
        val params = mapOf("type" to "drive")
        coEvery { repository.providers() } returns emptyList()
        coEvery { repository.getRemoteParams("gdrive") } returns Result.success(params)

        val vm = viewModel()
        vm.beginEditRemote("gdrive")
        advanceUntilIdle()

        vm.dismissEditRemote()
        runCurrent()

        assertThat(vm.uiState.value.editMode).isNull()
    }

    // C1 regression: schema must be awaited before editMode is exposed so that
    // passwordKeys is always correct and obscured secrets never leak into the form.
    @Test fun `beginEditRemote awaits schema before exposing editMode - obscured pass not in passwordKeys`() =
        runTest(mainDispatcher.dispatcher) {
            val passOption = RemoteOption(
                name = "pass", help = "Password", type = "string",
                required = false, isPassword = true, default = null,
                examples = emptyList(), advanced = false,
            )
            val hostOption = RemoteOption(
                name = "host", help = "Host", type = "string",
                required = false, isPassword = false, default = null,
                examples = emptyList(), advanced = false,
            )
            // Remote params include an obscured password value from config/get.
            val loaded = mapOf("type" to "sftp", "host" to "example.com", "pass" to "OBSCURED_RCLONE_VALUE")
            // beginEditRemote will call repository.providers() directly since schema is unloaded.
            coEvery { repository.providers() } returns listOf(
                RemoteProvider("sftp", "SFTP", listOf(hostOption, passOption))
            )
            coEvery { repository.getRemoteParams("sftp1") } returns Result.success(loaded)

            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }

            // Do NOT call ensureProvidersLoaded() first — this simulates the user tapping
            // Edit before the dialog has mounted and triggered a schema load.
            vm.beginEditRemote("sftp1")
            advanceUntilIdle()

            val editMode = vm.uiState.value.editMode
            assertThat(editMode).isNotNull()
            // The schema was awaited inside beginEditRemote: passwordKeys must include "pass".
            assertThat(editMode!!.passwordKeys).contains("pass")
            // The obscured value is in loadedParams (for diffing) but is listed as a password key,
            // so the dialog seeding will exclude it from editTypedValues — it never surfaces in UI.
            assertThat(editMode.loadedParams).containsEntry("pass", "OBSCURED_RCLONE_VALUE")
            // Verify repository.providers() was called (schema was fetched inside beginEditRemote).
            coVerify { repository.providers() }
            collector.cancel()
        }

    @Test fun `beginEditRemote with schema already loaded reuses cache and does not re-fetch providers`() =
        runTest(mainDispatcher.dispatcher) {
            val passOption = RemoteOption(
                name = "token", help = "Token", type = "string",
                required = false, isPassword = true, default = null,
                examples = emptyList(), advanced = false,
            )
            coEvery { repository.providers() } returns listOf(
                RemoteProvider("drive", "Google Drive", listOf(passOption))
            )
            coEvery { repository.getRemoteParams("gdrive") } returns Result.success(
                mapOf("type" to "drive", "token" to "OBSCURED")
            )

            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            // Pre-load the schema once.
            vm.ensureProvidersLoaded()
            advanceUntilIdle()

            // Now tap Edit — schema is cached, beginEditRemote must NOT re-fetch.
            vm.beginEditRemote("gdrive")
            advanceUntilIdle()

            val editMode = vm.uiState.value.editMode
            assertThat(editMode).isNotNull()
            assertThat(editMode!!.passwordKeys).contains("token")
            // providers() called exactly once (from ensureProvidersLoaded), not again.
            coVerify(exactly = 1) { repository.providers() }
            collector.cancel()
        }

    // --- ProviderCatalog exposure -----------------------------------------------

    @Test
    fun `pickerEntries returns catalog entries after providers loaded`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.providers() } returns listOf(
            RemoteProvider("drive", "Google Drive", listOf(
                RemoteOption("client_id", "", "string", false, false, null, emptyList(), false),
                RemoteOption("token", "", "string", false, false, null, emptyList(), true),
            )),
            RemoteProvider("sftp", "SFTP", listOf(
                RemoteOption("host", "", "string", true, false, null, emptyList(), false),
            )),
            RemoteProvider("crypt", "Encrypt/Decrypt", listOf(
                RemoteOption("remote", "", "string", true, false, null, emptyList(), false),
            )),
        )
        val vm = viewModel()
        vm.ensureProvidersLoaded()
        advanceUntilIdle()

        val entries = vm.pickerEntries()
        assertThat(entries).isNotNull()
        assertThat(entries!!.map { it.type }).containsAtLeast("drive", "sftp", "crypt")
    }

    @Test
    fun `providersReady distinguishes loading from loaded-but-empty so the dialog falls back`() =
        runTest(mainDispatcher.dispatcher) {
            // HIGH regression vs v0.1.0: a FAILED/EMPTY schema load is reported by
            // the repository as an empty list, which leaves pickerEntries() null —
            // indistinguishable from STILL-LOADING. providersReady must flip true
            // once the load finishes (even empty) so the Add dialog stops spinning
            // and falls through to the freeform fallback.
            coEvery { repository.providers() } returns emptyList()
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.providersReady.collect {} }
            advanceUntilIdle()

            // Before any load attempt: not ready (still "loading" from the UI's view).
            assertThat(vm.providersReady.value).isFalse()
            assertThat(vm.pickerEntries()).isNull()

            vm.ensureProvidersLoaded()
            advanceUntilIdle()

            // Load finished but produced no usable catalog: ready is true while
            // pickerEntries stays null — the two states are now distinguishable.
            assertThat(vm.providersReady.value).isTrue()
            assertThat(vm.pickerEntries()).isNull()
            collector.cancel()
        }

    @Test
    fun `providersReady becomes true with a usable catalog after a successful load`() =
        runTest(mainDispatcher.dispatcher) {
            coEvery { repository.providers() } returns listOf(
                RemoteProvider("sftp", "SFTP", listOf(
                    RemoteOption("host", "", "string", true, false, null, emptyList(), false),
                )),
            )
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.providersReady.collect {} }
            advanceUntilIdle()

            assertThat(vm.providersReady.value).isFalse()

            vm.ensureProvidersLoaded()
            advanceUntilIdle()

            assertThat(vm.providersReady.value).isTrue()
            assertThat(vm.pickerEntries()).isNotNull()
            collector.cancel()
        }

    @Test
    fun `setupKindFor classifies providers correctly`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.providers() } returns listOf(
            RemoteProvider("drive", "Google Drive", listOf(
                RemoteOption("client_id", "", "string", false, false, null, emptyList(), false),
                RemoteOption("token", "", "string", false, false, null, emptyList(), true),
            )),
            RemoteProvider("sftp", "SFTP", listOf(
                RemoteOption("host", "", "string", true, false, null, emptyList(), false),
            )),
            RemoteProvider("crypt", "Encrypt/Decrypt", listOf(
                RemoteOption("remote", "", "string", true, false, null, emptyList(), false),
            )),
        )
        val vm = viewModel()
        vm.ensureProvidersLoaded()
        advanceUntilIdle()

        assertThat(vm.setupKindFor("drive")).isEqualTo(SetupKind.OAuth(bundled = true))
        assertThat(vm.setupKindFor("sftp")).isEqualTo(SetupKind.Credential)
        assertThat(vm.setupKindFor("crypt")).isEqualTo(SetupKind.Wrapper)
    }

    // --- sensitiveKeys derivation & connectivity test --------------------------

    @Test
    fun `addRemote passes sensitiveKeys derived from schema`() = runTest(mainDispatcher.dispatcher) {
        val passOpt = RemoteOption(
            name = "pass", help = "", type = "string",
            required = false, isPassword = true, default = null,
            examples = emptyList(), advanced = false,
        )
        val hostOpt = RemoteOption(
            name = "host", help = "", type = "string",
            required = false, isPassword = false, default = null,
            examples = emptyList(), advanced = false,
        )
        coEvery { repository.providers() } returns listOf(
            RemoteProvider("ftp", "FTP", listOf(hostOpt, passOpt)),
        )
        coEvery { repository.addRemote(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.testConnectivity(any()) } returns Result.success(Unit)
        val vm = viewModel()
        vm.ensureProvidersLoaded()
        advanceUntilIdle()

        vm.addRemote(name = "myftp", type = "ftp", paramsText = "host=x\npass=secret", onResult = { _, _ -> })
        advanceUntilIdle()

        coVerify {
            repository.addRemote(
                name = "myftp",
                type = "ftp",
                params = any(),
                sensitiveKeys = setOf("pass"),
            )
        }
    }

    @Test
    fun `addRemote runs connectivity test after creation`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addRemote(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.testConnectivity("myremote") } returns Result.success(Unit)
        val vm = viewModel()

        vm.addRemote(name = "myremote", type = "s3", paramsText = "", onResult = { _, _ -> })
        advanceUntilIdle()

        coVerify { repository.testConnectivity("myremote") }
    }

    @Test
    fun `addRemote warns but keeps remote when connectivity test fails`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addRemote(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.testConnectivity("bad") } returns Result.failure(RuntimeException("timeout"))
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        var resultSuccess: Boolean? = null

        vm.addRemote(name = "bad", type = "s3", paramsText = "", onResult = { s, _ -> resultSuccess = s })
        advanceUntilIdle()

        assertThat(resultSuccess).isTrue()
        assertThat(vm.uiState.value.message).contains("could not verify connectivity")
        collector.cancel()
    }

    @Test
    fun `addRemote without provider schema surfaces freeform no-obscure warning`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.addRemote(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.testConnectivity(any()) } returns Result.success(Unit)
        // Schema never loaded → allOptionsForBackend returns null → sensitive keys
        // can't be derived, so passwords are stored un-obscured. Warn, don't block.
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        var resultSuccess: Boolean? = null

        vm.addRemote(name = "raw", type = "webdav", paramsText = "url=x\npass=secret", onResult = { s, _ -> resultSuccess = s })
        advanceUntilIdle()

        assertThat(resultSuccess).isTrue()
        assertThat(vm.uiState.value.message).contains("without obscuring")
        collector.cancel()
    }

    @Test
    fun `addRemote with provider schema does not surface no-obscure warning`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.providers() } returns listOf(
            RemoteProvider("ftp", "FTP", listOf(
                RemoteOption("host", "", "string", false, false, null, emptyList(), false),
            )),
        )
        coEvery { repository.addRemote(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.testConnectivity(any()) } returns Result.success(Unit)
        val vm = viewModel()
        vm.ensureProvidersLoaded()
        advanceUntilIdle()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.addRemote(name = "myftp", type = "ftp", paramsText = "host=x", onResult = { _, _ -> })
        advanceUntilIdle()

        assertThat(vm.uiState.value.message ?: "").doesNotContain("without obscuring")
        collector.cancel()
    }

    // --- startDaemonOAuth / cancelDaemonOAuth -----------------------------------

    @Test
    fun `startDaemonOAuth surfaces error on failure`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.withDaemonForOAuth<Unit>(any()) } throws
            app.lusk.virga.core.common.error.VirgaError.Rclone(message = "token exchange failed")
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "pcloud", name = "mypcloud")
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).contains("token exchange failed")
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        collector.cancel()
    }

    /**
     * A scripted stand-in for the orchestrator the VM news up internally.
     * The real one can't be driven from this module's tests: speaking the
     * config/create protocol needs kotlinx-serialization-json, which isn't on
     * the feature test compile classpath. The VM exposes a factory seam instead.
     */
    private fun scriptedOrchestrator(
        states: MutableStateFlow<DaemonOAuthOrchestrator.State>,
    ): DaemonOAuthOrchestrator = mockk(relaxUnitFun = true) {
        every { state } returns states
    }

    /** Mocks withDaemonForOAuth to execute its block (as the engine does, minus the Mutex). */
    private fun executeDaemonBlock() {
        coEvery { repository.withDaemonForOAuth<Any?>(any()) } coAnswers {
            firstArg<suspend (RcloneDaemon) -> Any?>()(mockk(relaxed = true))
        }
    }

    @Test
    fun `startDaemonOAuth sets oauthInProgress false after completion`() = runTest(mainDispatcher.dispatcher) {
        val states = MutableStateFlow<DaemonOAuthOrchestrator.State>(
            DaemonOAuthOrchestrator.State.Complete("mypcloud"),
        )
        executeDaemonBlock()
        coEvery { repository.testConnectivity(any()) } returns Result.success(Unit)
        coEvery { repository.refresh() } returns Result.success(Unit)
        val vm = viewModel()
        vm.daemonOAuthOrchestratorFactory = { scriptedOrchestrator(states) }
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "pcloud", name = "mypcloud")
        advanceUntilIdle()

        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        collector.cancel()
    }

    @Test
    fun `startDaemonOAuth runs testConnectivity only AFTER withDaemonForOAuth returns`() = runTest(mainDispatcher.dispatcher) {
        // Deadlock regression (CRITICAL): the engine holds a NON-reentrant Mutex
        // for the whole withDaemonForOAuth block, and testConnectivity acquires
        // that same Mutex — calling it from inside the block deadlocks
        // permanently. Verify the call happens strictly after the block returned.
        val states = MutableStateFlow<DaemonOAuthOrchestrator.State>(
            DaemonOAuthOrchestrator.State.Complete("mypcloud"),
        )
        var insideBlock = false
        var connectivityCalledInsideBlock: Boolean? = null
        coEvery { repository.withDaemonForOAuth<Any?>(any()) } coAnswers {
            insideBlock = true
            val result = firstArg<suspend (RcloneDaemon) -> Any?>()(mockk(relaxed = true))
            insideBlock = false
            result
        }
        coEvery { repository.testConnectivity("mypcloud") } coAnswers {
            connectivityCalledInsideBlock = insideBlock
            Result.success(Unit)
        }
        coEvery { repository.refresh() } returns Result.success(Unit)
        val vm = viewModel()
        vm.daemonOAuthOrchestratorFactory = { scriptedOrchestrator(states) }
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "pcloud", name = "mypcloud")
        advanceUntilIdle()

        assertThat(connectivityCalledInsideBlock).isFalse()
        coVerifyOrder {
            repository.withDaemonForOAuth<Any?>(any())
            repository.testConnectivity("mypcloud")
        }
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        collector.cancel()
    }

    @Test
    fun `second startDaemonOAuth while in progress is a no-op`() = runTest(mainDispatcher.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.withDaemonForOAuth<Any?>(any()) } coAnswers {
            gate.await()
            DaemonOAuthOrchestrator.State.Cancelled
        }
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "pcloud", name = "one")
        runCurrent()
        assertThat(vm.uiState.value.oauthInProgress).isTrue()

        // Second tap during the active flow must not re-enter withDaemonForOAuth.
        vm.startDaemonOAuth(type = "pcloud", name = "two")
        runCurrent()
        coVerify(exactly = 1) { repository.withDaemonForOAuth<Any?>(any()) }

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        coVerify(exactly = 1) { repository.withDaemonForOAuth<Any?>(any()) }
        collector.cancel()
    }

    @Test
    fun `awaiting token paste surfaces prompt and submitDaemonOAuthToken forwards it`() = runTest(mainDispatcher.dispatcher) {
        val states = MutableStateFlow<DaemonOAuthOrchestrator.State>(DaemonOAuthOrchestrator.State.Starting)
        val orchestrator = scriptedOrchestrator(states)
        every { orchestrator.submitToken(any()) } answers {
            // The real orchestrator resumes its state machine and completes.
            states.value = DaemonOAuthOrchestrator.State.Complete("mypcloud")
        }
        executeDaemonBlock()
        coEvery { repository.testConnectivity(any()) } returns Result.success(Unit)
        coEvery { repository.refresh() } returns Result.success(Unit)
        val vm = viewModel()
        vm.daemonOAuthOrchestratorFactory = { orchestrator }
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "pcloud", name = "mypcloud")
        runCurrent()
        states.value = DaemonOAuthOrchestrator.State.AwaitingTokenPaste(
            "Execute rclone authorize \"pcloud\" then paste the result.",
        )
        runCurrent()

        // Not a terminal state: the prompt shows while the flow stays in progress.
        assertThat(vm.uiState.value.daemonOAuthTokenPrompt).contains("rclone authorize")
        assertThat(vm.uiState.value.oauthInProgress).isTrue()

        vm.submitDaemonOAuthToken("""{"access_token":"tok"}""")
        advanceUntilIdle()

        verify { orchestrator.submitToken("""{"access_token":"tok"}""") }
        assertThat(vm.uiState.value.daemonOAuthTokenPrompt).isNull()
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        coVerify { repository.testConnectivity("mypcloud") }
        collector.cancel()
    }

    @Test
    fun `daemon oauth failure clears paste prompt`() = runTest(mainDispatcher.dispatcher) {
        val states = MutableStateFlow<DaemonOAuthOrchestrator.State>(
            DaemonOAuthOrchestrator.State.AwaitingTokenPaste("paste it"),
        )
        executeDaemonBlock()
        val vm = viewModel()
        vm.daemonOAuthOrchestratorFactory = { scriptedOrchestrator(states) }
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "pcloud", name = "mypcloud")
        runCurrent()
        assertThat(vm.uiState.value.daemonOAuthTokenPrompt).isEqualTo("paste it")

        states.value = DaemonOAuthOrchestrator.State.Failed("token exchange failed")
        advanceUntilIdle()

        assertThat(vm.uiState.value.daemonOAuthTokenPrompt).isNull()
        assertThat(vm.uiState.value.message).contains("token exchange failed")
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        collector.cancel()
    }

    @Test
    fun `failed daemon oauth deletes the phantom remote`() = runTest(mainDispatcher.dispatcher) {
        // rclone-M1: rclone persists the remote BEFORE the question machine finishes,
        // so a Failed terminal leaves a token-less phantom. The flow must remove it
        // best-effort once the engine lock is released.
        val states = MutableStateFlow<DaemonOAuthOrchestrator.State>(
            DaemonOAuthOrchestrator.State.Failed("token exchange failed"),
        )
        executeDaemonBlock()
        coEvery { repository.deleteRemote(any()) } returns Result.success(Unit)
        val vm = viewModel()
        vm.daemonOAuthOrchestratorFactory = { scriptedOrchestrator(states) }
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "pcloud", name = "  mypcloud  ")
        advanceUntilIdle()

        // Deleted by trimmed name, only after the daemon block returned.
        coVerify(exactly = 1) { repository.deleteRemote("mypcloud") }
        coVerifyOrder {
            repository.withDaemonForOAuth<Any?>(any())
            repository.deleteRemote("mypcloud")
        }
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
        collector.cancel()
    }

    @Test
    fun `cancelled daemon oauth deletes the phantom remote`() = runTest(mainDispatcher.dispatcher) {
        val states = MutableStateFlow<DaemonOAuthOrchestrator.State>(
            DaemonOAuthOrchestrator.State.Cancelled,
        )
        executeDaemonBlock()
        coEvery { repository.deleteRemote(any()) } returns Result.success(Unit)
        val vm = viewModel()
        vm.daemonOAuthOrchestratorFactory = { scriptedOrchestrator(states) }
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "pcloud", name = "mypcloud")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.deleteRemote("mypcloud") }
        collector.cancel()
    }

    @Test
    fun `completed daemon oauth does NOT delete the remote`() = runTest(mainDispatcher.dispatcher) {
        val states = MutableStateFlow<DaemonOAuthOrchestrator.State>(
            DaemonOAuthOrchestrator.State.Complete("mypcloud"),
        )
        executeDaemonBlock()
        coEvery { repository.testConnectivity(any()) } returns Result.success(Unit)
        coEvery { repository.refresh() } returns Result.success(Unit)
        val vm = viewModel()
        vm.daemonOAuthOrchestratorFactory = { scriptedOrchestrator(states) }
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.startDaemonOAuth(type = "pcloud", name = "mypcloud")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.deleteRemote(any()) }
        collector.cancel()
    }

    @Test
    fun `startDaemonOAuth with blank name early-returns without entering the daemon block`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.startDaemonOAuth(type = "pcloud", name = "   ")
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.withDaemonForOAuth<Any?>(any()) }
            assertThat(vm.uiState.value.oauthInProgress).isFalse()
            assertThat(vm.uiState.value.message).isNotNull()
            collector.cancel()
        }

    @Test
    fun `cancelDaemonOAuth when no orchestrator is a no-op`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        // Should not throw
        vm.cancelDaemonOAuth()
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
    }

    @Test
    fun `oauthInProgress is false by default`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        assertThat(vm.uiState.value.oauthInProgress).isFalse()
    }

    // --- encrypted config export/import (0.3.0 D2) ----------------------------

    /** Stubs context to resolve the R.string.remotes_msg_config_exported_encrypted resource. */
    private fun stubEncryptedExportString() {
        every { context.getString(R.string.remotes_msg_config_exported_encrypted) } returns
            "Encrypted config exported."
    }

    private fun stubExportString() {
        every { context.getString(R.string.remotes_msg_config_exported) } returns "Config exported."
    }

    private fun stubExportNothingString() {
        every { context.getString(R.string.remotes_msg_export_nothing) } returns
            "No remotes configured yet, so there's nothing to export."
    }

    private fun stubImportWrongPassphraseString() {
        every { context.getString(R.string.remotes_msg_import_wrong_passphrase) } returns
            "Wrong passphrase — the file could not be decrypted. Please try again."
    }

    /** Builds a mock Uri whose ContentResolver returns an OutputStream that captures writes. */
    private fun uriWithOutputStream(capture: java.io.ByteArrayOutputStream): android.net.Uri {
        val resolver: android.content.ContentResolver = mockk {
            every { openOutputStream(any(), any()) } returns capture
        }
        every { context.contentResolver } returns resolver
        return mockk()
    }

    @Test
    fun `exportConfigToUri with null passphrase writes plaintext bytes`() =
        runTest(mainDispatcher.dispatcher) {
            val confText = "[r]\ntype = drive\n"
            coEvery { repository.exportConfig() } returns confText
            stubExportString()
            val out = java.io.ByteArrayOutputStream()
            val uri = uriWithOutputStream(out)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.exportConfigToUri(uri, null)
            advanceUntilIdle()

            assertThat(out.toByteArray().toString(Charsets.UTF_8)).isEqualTo(confText)
            assertThat(vm.uiState.value.message).isEqualTo("Config exported.")
            collector.cancel()
        }

    @Test
    fun `exportConfigToUri with non-null passphrase writes an encrypted container`() =
        runTest(mainDispatcher.dispatcher) {
            val confText = "[r]\ntype = drive\n"
            coEvery { repository.exportConfig() } returns confText
            stubEncryptedExportString()
            val out = java.io.ByteArrayOutputStream()
            val uri = uriWithOutputStream(out)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.exportConfigToUri(uri, "secret".toCharArray())
            advanceUntilIdle()

            val written = out.toByteArray()
            assertThat(app.lusk.virga.core.rclone.crypto.ConfigCrypto.isEncryptedContainer(written))
                .isTrue()
            collector.cancel()
        }

    @Test
    fun `exportConfigToUri encrypted container decrypts back to original config`() =
        runTest(mainDispatcher.dispatcher) {
            val confText = "[r]\ntype = b2\naccount = abc\n"
            coEvery { repository.exportConfig() } returns confText
            stubEncryptedExportString()
            val out = java.io.ByteArrayOutputStream()
            val uri = uriWithOutputStream(out)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.exportConfigToUri(uri, "roundtrip".toCharArray())
            advanceUntilIdle()

            val decrypted = app.lusk.virga.core.rclone.crypto.ConfigCrypto.decrypt(
                out.toByteArray(),
                "roundtrip".toCharArray(),
            )
            assertThat(decrypted).isEqualTo(confText)
            collector.cancel()
        }

    @Test
    fun `importConfigFromUri with encrypted container and no passphrase sets pendingEncryptedImport`() =
        runTest(mainDispatcher.dispatcher) {
            val encrypted = app.lusk.virga.core.rclone.crypto.ConfigCrypto.encrypt(
                "[r]\ntype = s3\n",
                "secret".toCharArray(),
            )
            val uri = uriReturning(encrypted)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            // Call the no-passphrase overload — must prompt rather than import
            vm.importConfigFromUri(uri)
            advanceUntilIdle()

            assertThat(vm.uiState.value.pendingEncryptedImport).isNotNull()
            coVerify(exactly = 0) { repository.importConfig(any()) }
            collector.cancel()
        }

    @Test
    fun `importConfigFromUri with correct passphrase calls repository with decrypted text`() =
        runTest(mainDispatcher.dispatcher) {
            val originalText = "[r]\ntype = s3\n"
            val encrypted = app.lusk.virga.core.rclone.crypto.ConfigCrypto.encrypt(
                originalText,
                "correct".toCharArray(),
            )
            val uri = uriReturning(encrypted)
            coEvery { repository.importConfig(any()) } returns Result.success(Unit)
            every { context.getString(R.string.remotes_msg_config_imported) } returns "Config imported."
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.importConfigFromUri(uri, "correct".toCharArray())
            advanceUntilIdle()

            coVerify { repository.importConfig(originalText) }
            assertThat(vm.uiState.value.pendingEncryptedImport).isNull()
            collector.cancel()
        }

    @Test
    fun `importConfigFromUri with wrong passphrase sets wrong-passphrase message and keeps prompt`() =
        runTest(mainDispatcher.dispatcher) {
            val encrypted = app.lusk.virga.core.rclone.crypto.ConfigCrypto.encrypt(
                "[r]\ntype = s3\n",
                "correct".toCharArray(),
            )
            val uri = uriReturning(encrypted)
            stubImportWrongPassphraseString()
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.importConfigFromUri(uri, "wrong".toCharArray())
            advanceUntilIdle()

            assertThat(vm.uiState.value.message).contains("Wrong passphrase")
            assertThat(vm.uiState.value.pendingEncryptedImport).isNotNull()
            coVerify(exactly = 0) { repository.importConfig(any()) }
            collector.cancel()
        }

    @Test
    fun `importConfigFromUri with plaintext bytes uses existing import path`() =
        runTest(mainDispatcher.dispatcher) {
            val confText = "[r]\ntype = drive\n"
            val uri = uriReturning(confText.toByteArray(Charsets.UTF_8))
            coEvery { repository.importConfig(confText) } returns Result.success(Unit)
            every { context.getString(R.string.remotes_msg_config_imported) } returns "Config imported."
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.importConfigFromUri(uri)
            advanceUntilIdle()

            coVerify { repository.importConfig(confText) }
            assertThat(vm.uiState.value.pendingEncryptedImport).isNull()
            assertThat(vm.uiState.value.message).isEqualTo("Config imported.")
            collector.cancel()
        }

    @Test
    fun `dismissImportPassphrase clears pendingEncryptedImport`() =
        runTest(mainDispatcher.dispatcher) {
            val encrypted = app.lusk.virga.core.rclone.crypto.ConfigCrypto.encrypt(
                "[r]\ntype = s3\n",
                "pass".toCharArray(),
            )
            val uri = uriReturning(encrypted)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            // Trigger the prompt
            vm.importConfigFromUri(uri)
            advanceUntilIdle()
            assertThat(vm.uiState.value.pendingEncryptedImport).isNotNull()

            vm.dismissImportPassphrase()
            advanceUntilIdle()

            assertThat(vm.uiState.value.pendingEncryptedImport).isNull()
            collector.cancel()
        }

    // --- rename remote (0.3.0 A2) ------------------------------------------------

    private fun remoteNamed(name: String) = Remote(name = name, type = "s3")

    @Test
    fun `beginRenameRemote sets renameTarget and clears editMode`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.beginRenameRemote("myremote")
            advanceUntilIdle()

            assertThat(vm.uiState.value.renameTarget).isEqualTo("myremote")
            assertThat(vm.uiState.value.editMode).isNull()
            collector.cancel()
        }

    @Test
    fun `dismissRenameRemote clears renameTarget and renameInFlight`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.beginRenameRemote("myremote")
            advanceUntilIdle()
            assertThat(vm.uiState.value.renameTarget).isNotNull()

            vm.dismissRenameRemote()
            advanceUntilIdle()

            assertThat(vm.uiState.value.renameTarget).isNull()
            assertThat(vm.uiState.value.renameInFlight).isFalse()
            collector.cancel()
        }

    @Test
    fun `submitRename with blank name calls onValidationError without calling repository`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            var error: String? = null

            vm.submitRename("old", "   ") { error = it }
            advanceUntilIdle()

            assertThat(error).isEqualTo("Name must not be blank.")
            coVerify(exactly = 0) { repository.renameRemote(any(), any()) }
            collector.cancel()
        }

    @Test
    fun `submitRename with same name calls onValidationError without calling repository`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            var error: String? = null

            vm.submitRename("myremote", "myremote") { error = it }
            advanceUntilIdle()

            assertThat(error).isEqualTo("New name is the same as the current name.")
            coVerify(exactly = 0) { repository.renameRemote(any(), any()) }
            collector.cancel()
        }

    @Test
    fun `submitRename with name already taken calls onValidationError without calling repository`() =
        runTest(mainDispatcher.dispatcher) {
            remotesFlow.value = listOf(remoteNamed("taken"), remoteNamed("old"))
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            var error: String? = null

            vm.submitRename("old", "taken") { error = it }
            advanceUntilIdle()

            assertThat(error).contains("taken")
            coVerify(exactly = 0) { repository.renameRemote(any(), any()) }
            collector.cancel()
        }

    @Test
    fun `submitRename with invalid chars calls onValidationError without calling repository`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            var error: String? = null

            vm.submitRename("old", "bad:name") { error = it }
            advanceUntilIdle()

            assertThat(error).isNotNull()
            coVerify(exactly = 0) { repository.renameRemote(any(), any()) }
            collector.cancel()
        }

    @Test
    fun `submitRename success calls repository, clears renameTarget, and sets confirmation message`() =
        runTest(mainDispatcher.dispatcher) {
            coEvery { repository.renameRemote("old", "newname") } returns Result.success(Unit)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            vm.beginRenameRemote("old")
            advanceUntilIdle()

            vm.submitRename("old", "newname") { }
            advanceUntilIdle()

            coVerify { repository.renameRemote("old", "newname") }
            assertThat(vm.uiState.value.renameTarget).isNull()
            assertThat(vm.uiState.value.renameInFlight).isFalse()
            assertThat(vm.uiState.value.message).isEqualTo("Renamed \"old\" to \"newname\".")
            collector.cancel()
        }

    @Test
    fun `submitRename trims whitespace before calling repository`() =
        runTest(mainDispatcher.dispatcher) {
            coEvery { repository.renameRemote("old", "trimmed") } returns Result.success(Unit)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.submitRename("old", "  trimmed  ") { }
            advanceUntilIdle()

            coVerify { repository.renameRemote("old", "trimmed") }
            collector.cancel()
        }

    @Test
    fun `submitRename failure surfaces error message and clears renameTarget`() =
        runTest(mainDispatcher.dispatcher) {
            coEvery { repository.renameRemote(any(), any()) } returns
                Result.failure(app.lusk.virga.core.common.error.VirgaError.Rclone(message = "rename failed"))
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()
            vm.beginRenameRemote("old")
            advanceUntilIdle()

            vm.submitRename("old", "newname") { }
            advanceUntilIdle()

            assertThat(vm.uiState.value.renameTarget).isNull()
            assertThat(vm.uiState.value.renameInFlight).isFalse()
            assertThat(vm.uiState.value.message).contains("rename failed")
            collector.cancel()
        }

    // --- reauthRemote / signOutRemote (A4) ----------------------------------------

    @Test
    fun `reauthRemote for unknown remote name is a no-op`() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        // "ghost" isn't in the remotes list — the call must be a no-op, no message.
        vm.reauthRemote("ghost")
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).isNull()
        assertThat(vm.uiState.value.reauthInProgress).isEmpty()
        collector.cancel()
    }

    @Test
    fun `reauthRemote for non-OAuth type surfaces reauth-failed message`() = runTest(mainDispatcher.dispatcher) {
        // S3 has no matching OAuthProvider — reauthRemote must report an error.
        remotesFlow.value = listOf(Remote(name = "mybucket", type = "s3"))
        every { context.getString(R.string.remotes_msg_reauth_failed, "mybucket") } returns
            "Re-authentication failed: mybucket"
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.reauthRemote("mybucket")
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).contains("Re-authentication failed")
        collector.cancel()
    }

    @Test
    fun `reauthRemote for drive type sets reauthInProgress and starts OAuth`() = runTest(mainDispatcher.dispatcher) {
        remotesFlow.value = listOf(Remote(name = "gdrive", type = "drive"))
        every { tokenExchanger.authorizeUrl(any()) } returns "https://accounts.google.example/auth?state=x"
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.reauthRemote("gdrive")
        advanceUntilIdle()

        // reauthInProgress should contain the remote name after initiating.
        assertThat(vm.uiState.value.reauthInProgress).contains("gdrive")
        // And the OAuth URL should have been published (SystemOAuthFlow was invoked).
        assertThat(vm.launchUrl.value).isNotNull()
        collector.cancel()
    }

    @Test
    fun `reauthRemote ignores a second tap while already in progress`() = runTest(mainDispatcher.dispatcher) {
        remotesFlow.value = listOf(Remote(name = "gdrive", type = "drive"))
        every { tokenExchanger.authorizeUrl(any()) } returns "https://accounts.google.example/auth?state=x"
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.reauthRemote("gdrive")
        advanceUntilIdle()
        // Second tap before the redirect arrives must short-circuit — no second OAuth launch.
        vm.reauthRemote("gdrive")
        advanceUntilIdle()

        verify(exactly = 1) { tokenExchanger.authorizeUrl(any()) }
        assertThat(vm.uiState.value.reauthInProgress).contains("gdrive")
        collector.cancel()
    }

    @Test
    fun `signOutRemote calls updateRemote with empty token then setNeedsReauth true`() =
        runTest(mainDispatcher.dispatcher) {
            coEvery { repository.updateRemote("gdrive", mapOf("token" to ""), emptySet()) } returns
                Result.success(Unit)
            every { context.getString(R.string.remotes_msg_sign_out_done, "gdrive") } returns
                "Signed out of \"gdrive\"."
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.signOutRemote("gdrive")
            advanceUntilIdle()

            coVerifyOrder {
                repository.updateRemote("gdrive", mapOf("token" to ""), emptySet())
                repository.setNeedsReauth("gdrive", true)
            }
            assertThat(vm.uiState.value.message).contains("Signed out")
            collector.cancel()
        }

    @Test
    fun `signOutRemote failure surfaces error message and does not call setNeedsReauth`() =
        runTest(mainDispatcher.dispatcher) {
            coEvery { repository.updateRemote("gdrive", any(), any()) } returns
                Result.failure(RuntimeException("engine unavailable"))
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.signOutRemote("gdrive")
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.setNeedsReauth(any(), any()) }
            assertThat(vm.uiState.value.message).contains("engine unavailable")
            collector.cancel()
        }

    // --- C1: re-auth success/failure needsReauth flag lifecycle ------------------

    @Test
    fun `reauthRemote success clears needsReauth flag and reauthInProgress`() =
        runTest(mainDispatcher.dispatcher) {
            // Drive the full re-auth round-trip: reauthRemote → OAuth flow → redirect result.
            remotesFlow.value = listOf(Remote(name = "gdrive", type = "drive", needsReauth = true))
            every { tokenExchanger.authorizeUrl(any()) } returns "https://accounts.google.example/auth?state=x"
            coEvery { tokenExchanger.exchange(any(), any()) } returns Result.success("""{"access_token":"new"}""")
            coEvery { tokenExchanger.providerConfigExtras(any(), any()) } returns Result.success(emptyMap())
            coEvery { repository.addRemote(any(), any(), any()) } returns Result.success(Unit)
            coEvery { repository.testConnectivity("gdrive") } returns Result.success(Unit)
            every { context.getString(R.string.remotes_msg_reauth_success, "gdrive") } returns
                "Re-authenticated \"gdrive\" successfully."
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.reauthRemote("gdrive")
            advanceUntilIdle()

            // Capture the pending state that was registered so we can emit a matching result.
            val pendingSlot = slot<OAuthTokenExchanger.PendingAuth>()
            coVerify { tokenExchanger.authorizeUrl(capture(pendingSlot)) }
            val pending = pendingSlot.captured

            // Emit a matching success redirect result.
            store.emit(OAuthResult.Success(state = pending.state, code = "auth-code"))
            advanceUntilIdle()

            // On success: setNeedsReauth(false) must be called and the flag cleared.
            coVerify { repository.setNeedsReauth("gdrive", false) }
            // reauthInProgress must be cleared after completion.
            assertThat(vm.uiState.value.reauthInProgress).doesNotContain("gdrive")
            assertThat(vm.uiState.value.message).contains("Re-authenticated")
            collector.cancel()
        }

    @Test
    fun `reauthRemote failure leaves needsReauth set and clears reauthInProgress`() =
        runTest(mainDispatcher.dispatcher) {
            remotesFlow.value = listOf(Remote(name = "gdrive", type = "drive", needsReauth = true))
            every { tokenExchanger.authorizeUrl(any()) } returns "https://accounts.google.example/auth?state=x"
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.reauthRemote("gdrive")
            advanceUntilIdle()

            val pendingSlot = slot<OAuthTokenExchanger.PendingAuth>()
            coVerify { tokenExchanger.authorizeUrl(capture(pendingSlot)) }

            // Emit a matching error redirect result (e.g. user denied).
            store.emit(OAuthResult.Error(state = pendingSlot.captured.state, message = "access_denied"))
            advanceUntilIdle()

            // setNeedsReauth must NOT be called — flag stays true, badge persists.
            coVerify(exactly = 0) { repository.setNeedsReauth("gdrive", false) }
            // reauthInProgress must still be cleared (so the spinner is gone).
            assertThat(vm.uiState.value.reauthInProgress).doesNotContain("gdrive")
            collector.cancel()
        }
}
