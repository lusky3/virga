package app.lusk.virga.feature.remotes

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.datastore.OAuthKeyStore
import app.lusk.virga.core.rclone.api.RcApiClient
import app.lusk.virga.core.rclone.oauth.OAuthConfig
import app.lusk.virga.core.rclone.oauth.OAuthProviders
import app.lusk.virga.core.rclone.oauth.OAuthStore
import app.lusk.virga.core.rclone.oauth.OAuthTokenExchanger
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayOutputStream

/**
 * ViewModel tests for the single-remote export feature (0.3.0 A5):
 * [RemotesViewModel.beginSingleRemoteExport], [RemotesViewModel.dismissSingleRemoteExport],
 * [RemotesViewModel.exportRemoteSectionToUri], and [RemotesViewModel.exportConfigToUri]
 * with `redacted = true`.
 *
 * Separated from the main [RemotesViewModelTest] to keep file length within the
 * 500-line budget enforced by CLAUDE.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemotesViewModelSingleExportTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val remotesFlow = MutableStateFlow<List<app.lusk.virga.core.common.model.Remote>>(emptyList())
    private val repository: RemoteRepository = mockk(relaxed = true) {
        every { remotes } returns remotesFlow
    }
    private val tokenExchanger: OAuthTokenExchanger = mockk(relaxed = true)
    private val store = OAuthStore()
    private val keyStore: OAuthKeyStore = mockk(relaxed = true) {
        every { clientIds } returns MutableStateFlow(emptyMap())
        coEvery { clientId(any()) } returns null
    }
    private val context: Context = mockk {
        every { getString(any()) } answers {
            val id = firstArg<Int>()
            when (id) {
                R.string.remotes_msg_config_exported -> "Config exported."
                R.string.remotes_msg_config_exported_encrypted -> "Encrypted config exported."
                R.string.remotes_msg_export_nothing -> "No remotes configured yet."
                R.string.remotes_msg_export_failed -> "Could not write the config file."
                R.string.remotes_msg_config_imported -> "Config imported."
                R.string.remotes_msg_import_too_large ->
                    "Config file is too large to import (max 256 KB)."
                R.string.remotes_msg_state_mismatch -> "Sign-in state mismatch; please try again."
                R.string.remotes_msg_could_not_save -> "Could not save remote."
                R.string.remotes_rename_error_blank -> "Name must not be blank."
                R.string.remotes_rename_error_same -> "New name is the same as the current name."
                R.string.remotes_add_name_invalid -> "Name can't contain ':' or '/'"
                else -> "string#$id"
            }
        }
        every { getString(any(), *anyVararg()) } answers {
            val id = firstArg<Int>()
            val args = args.drop(1).flatMap { if (it is Array<*>) it.toList() else listOf(it) }
            when (id) {
                R.string.remotes_msg_section_exported -> "Remote \"${args[0]}\" exported."
                R.string.remotes_msg_oauth_not_configured ->
                    "${args[0]} OAuth client ID isn't configured yet."
                R.string.remotes_msg_sign_in_failed -> "Sign-in failed: ${args[0]}"
                R.string.remotes_msg_added_remote -> "Added ${args[0]} remote \"${args[1]}\""
                R.string.remotes_msg_connectivity_warning ->
                    "Remote \"${args[0]}\" was saved, but could not verify connectivity."
                R.string.remotes_msg_remote_updated -> "Remote \"${args[0]}\" updated."
                R.string.remotes_rename_error_taken -> "\"${args[0]}\" is already used."
                R.string.remotes_msg_remote_renamed -> "Renamed \"${args[0]}\" to \"${args[1]}\"."
                else -> "string#$id(${args.joinToString()})"
            }
        }
    }
    private val apiClient: RcApiClient = mockk(relaxed = true)
    private val testDispatchers = object : app.lusk.virga.core.common.dispatchers.DispatcherProvider {
        override val main = mainDispatcher.dispatcher
        override val default = mainDispatcher.dispatcher
        override val io = mainDispatcher.dispatcher
    }

    private fun oauthConfig() = OAuthConfig(
        defaultRedirectUri = "virga://oauth/callback",
        clientIds = mapOf(
            OAuthProviders.GoogleDrive.id to "client-google",
            OAuthProviders.OneDrive.id to "",
            OAuthProviders.Dropbox.id to "client-dropbox",
        ),
    )

    private fun viewModel() = RemotesViewModel(
        context, repository, oauthConfig(), store, tokenExchanger, keyStore,
        apiClient, testDispatchers, PendingRemoteResult(),
    )

    private fun uriWithOutputStream(capture: ByteArrayOutputStream): Uri {
        val resolver: ContentResolver = mockk {
            every { openOutputStream(any(), any()) } returns capture
        }
        every { context.contentResolver } returns resolver
        return mockk()
    }

    // --- beginSingleRemoteExport / dismissSingleRemoteExport ---------------------

    @Test
    fun `beginSingleRemoteExport sets singleExportRemote in uiState`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.beginSingleRemoteExport("gdrive")
            advanceUntilIdle()

            assertThat(vm.uiState.value.singleExportRemote).isEqualTo("gdrive")
            collector.cancel()
        }

    @Test
    fun `dismissSingleRemoteExport clears singleExportRemote`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.beginSingleRemoteExport("gdrive")
            advanceUntilIdle()
            assertThat(vm.uiState.value.singleExportRemote).isNotNull()

            vm.dismissSingleRemoteExport()
            advanceUntilIdle()

            assertThat(vm.uiState.value.singleExportRemote).isNull()
            collector.cancel()
        }

    @Test
    fun `beginSingleRemoteExport followed by dismiss leaves singleExportRemote null`() =
        runTest(mainDispatcher.dispatcher) {
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.beginSingleRemoteExport("mysftp")
            advanceUntilIdle()
            vm.dismissSingleRemoteExport()
            advanceUntilIdle()

            assertThat(vm.uiState.value.singleExportRemote).isNull()
            collector.cancel()
        }

    // --- exportRemoteSectionToUri — section export --------------------------------

    @Test
    fun `exportRemoteSectionToUri calls exportConfigSection with remoteName`() =
        runTest(mainDispatcher.dispatcher) {
            val sectionText = "[mysftp]\ntype = sftp\nhost = example.com\n"
            coEvery { repository.exportConfigSection("mysftp") } returns sectionText
            val out = ByteArrayOutputStream()
            val uri = uriWithOutputStream(out)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.exportRemoteSectionToUri("mysftp", uri, redacted = false)
            advanceUntilIdle()

            coVerify { repository.exportConfigSection("mysftp") }
            assertThat(out.toByteArray().toString(Charsets.UTF_8)).isEqualTo(sectionText)
            collector.cancel()
        }

    @Test
    fun `exportRemoteSectionToUri sets section-exported message on success`() =
        runTest(mainDispatcher.dispatcher) {
            coEvery { repository.exportConfigSection("mysftp") } returns "[mysftp]\ntype = sftp\n"
            val out = ByteArrayOutputStream()
            val uri = uriWithOutputStream(out)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.exportRemoteSectionToUri("mysftp", uri, redacted = false)
            advanceUntilIdle()

            assertThat(vm.uiState.value.message).isEqualTo("Remote \"mysftp\" exported.")
            collector.cancel()
        }

    @Test
    fun `exportRemoteSectionToUri redacted calls exportConfigSectionRedacted`() =
        runTest(mainDispatcher.dispatcher) {
            val redactedText = "[mysftp]\ntype = sftp\nhost = REDACTED\n"
            coEvery { repository.exportConfigSectionRedacted("mysftp") } returns redactedText
            val out = ByteArrayOutputStream()
            val uri = uriWithOutputStream(out)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.exportRemoteSectionToUri("mysftp", uri, redacted = true)
            advanceUntilIdle()

            coVerify { repository.exportConfigSectionRedacted("mysftp") }
            coVerify(exactly = 0) { repository.exportConfigSection(any()) }
            assertThat(out.toByteArray().toString(Charsets.UTF_8)).isEqualTo(redactedText)
            collector.cancel()
        }

    @Test
    fun `exportRemoteSectionToUri empty section sets export-nothing message`() =
        runTest(mainDispatcher.dispatcher) {
            coEvery { repository.exportConfigSection("ghost") } returns ""
            val out = ByteArrayOutputStream()
            val uri = uriWithOutputStream(out)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.exportRemoteSectionToUri("ghost", uri, redacted = false)
            advanceUntilIdle()

            assertThat(vm.uiState.value.message).isEqualTo("No remotes configured yet.")
            assertThat(out.size()).isEqualTo(0)
            collector.cancel()
        }

    // --- exportConfigToUri with redacted = true ------------------------------------

    @Test
    fun `exportConfigToUri redacted true calls exportConfigRedacted`() =
        runTest(mainDispatcher.dispatcher) {
            val redactedText = "[r]\ntype = drive\nclient_id = REDACTED\n"
            coEvery { repository.exportConfigRedacted() } returns redactedText
            val out = ByteArrayOutputStream()
            val uri = uriWithOutputStream(out)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.exportConfigToUri(uri, redacted = true)
            advanceUntilIdle()

            coVerify { repository.exportConfigRedacted() }
            coVerify(exactly = 0) { repository.exportConfig() }
            assertThat(out.toByteArray().toString(Charsets.UTF_8)).isEqualTo(redactedText)
            collector.cancel()
        }

    @Test
    fun `exportConfigToUri redacted true sets exported message`() =
        runTest(mainDispatcher.dispatcher) {
            coEvery { repository.exportConfigRedacted() } returns "[r]\ntype = drive\n"
            val out = ByteArrayOutputStream()
            val uri = uriWithOutputStream(out)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.exportConfigToUri(uri, redacted = true)
            advanceUntilIdle()

            assertThat(vm.uiState.value.message).isEqualTo("Config exported.")
            collector.cancel()
        }

    @Test
    fun `exportConfigToUri redacted true with empty config sets export-nothing message`() =
        runTest(mainDispatcher.dispatcher) {
            coEvery { repository.exportConfigRedacted() } returns ""
            val out = ByteArrayOutputStream()
            val uri = uriWithOutputStream(out)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.exportConfigToUri(uri, redacted = true)
            advanceUntilIdle()

            assertThat(vm.uiState.value.message).isEqualTo("No remotes configured yet.")
            assertThat(out.size()).isEqualTo(0)
            collector.cancel()
        }

    // --- importConfigFromUri merge overload (0.3.0 A5 D5) -------------------------

    @Test
    fun `importConfigFromUri mergeMode true calls importConfigMerged`() =
        runTest(mainDispatcher.dispatcher) {
            val confText = "[r]\ntype = drive\n"
            val stream = java.io.ByteArrayInputStream(confText.toByteArray(Charsets.UTF_8))
            val resolver: ContentResolver = mockk {
                every { openInputStream(any()) } returns stream
            }
            every { context.contentResolver } returns resolver
            val uri: Uri = mockk()
            coEvery { repository.importConfigMerged(any()) } returns Result.success(Unit)
            every { context.getString(R.string.remotes_msg_config_imported_merged) } returns
                "Config merged."
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.importConfigFromUri(uri, mergeMode = true)
            advanceUntilIdle()

            coVerify { repository.importConfigMerged(confText) }
            coVerify(exactly = 0) { repository.importConfig(any()) }
            assertThat(vm.uiState.value.message).isEqualTo("Config merged.")
            collector.cancel()
        }

    @Test
    fun `importConfigFromUri mergeMode false calls importConfig`() =
        runTest(mainDispatcher.dispatcher) {
            val confText = "[r]\ntype = s3\n"
            val stream = java.io.ByteArrayInputStream(confText.toByteArray(Charsets.UTF_8))
            val resolver: ContentResolver = mockk {
                every { openInputStream(any()) } returns stream
            }
            every { context.contentResolver } returns resolver
            val uri: Uri = mockk()
            coEvery { repository.importConfig(any()) } returns Result.success(Unit)
            val vm = viewModel()
            val collector = backgroundScope.launch { vm.uiState.collect {} }
            advanceUntilIdle()

            vm.importConfigFromUri(uri, mergeMode = false)
            advanceUntilIdle()

            coVerify { repository.importConfig(confText) }
            coVerify(exactly = 0) { repository.importConfigMerged(any()) }
            assertThat(vm.uiState.value.message).isEqualTo("Config imported.")
            collector.cancel()
        }
}
