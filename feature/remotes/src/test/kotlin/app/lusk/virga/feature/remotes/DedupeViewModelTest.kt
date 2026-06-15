package app.lusk.virga.feature.remotes

import android.content.Context
import app.lusk.virga.core.data.PendingRemoteResult
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.datastore.OAuthKeyStore
import app.lusk.virga.core.common.model.Remote
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

/**
 * Tests for [RemotesViewModel.dedupeRemote]. Kept in a separate file so the
 * parent RemotesViewModelTest stays under the 500-line file-size limit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DedupeViewModelTest {

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
            when (id) {
                R.string.remotes_dedupe_success -> "Dedupe complete on \"${rawArgs[0]}\"."
                R.string.remotes_dedupe_failed -> "Dedupe failed on \"${rawArgs[0]}\": ${rawArgs.getOrNull(1)}"
                else -> "string#$id(${rawArgs.joinToString()})"
            }
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

    @Test
    fun `dedupeRemote success surfaces success snackbar`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.dedupe("gdrive") } returns Result.success(Unit)
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.dedupeRemote("gdrive")
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).contains("gdrive")
        assertThat(vm.uiState.value.message).contains("complete")
        collector.cancel()
    }

    @Test
    fun `dedupeRemote failure surfaces failure snackbar`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.dedupe("s3") } returns Result.failure(RuntimeException("not supported"))
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.dedupeRemote("s3")
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).contains("s3")
        // The rclone reason must reach the snackbar, not be collapsed to a generic failure.
        assertThat(vm.uiState.value.message).contains("not supported")
        collector.cancel()
    }

    @Test
    fun `dedupeRemote calls repository dedupe with correct remote name`() = runTest(mainDispatcher.dispatcher) {
        coEvery { repository.dedupe(any()) } returns Result.success(Unit)
        val vm = viewModel()
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.dedupeRemote("dropbox")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.dedupe("dropbox") }
        collector.cancel()
    }
}
