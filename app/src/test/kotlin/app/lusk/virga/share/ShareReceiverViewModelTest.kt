package app.lusk.virga.share

import android.content.Context
import android.net.Uri
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.data.FileBrowserRepository
import app.lusk.virga.core.data.RemoteRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [ShareReceiverViewModel].
 *
 * # Design note — stageUri / safDisplayName seam
 * [ShareReceiverViewModel.uiState] calls [safDisplayName] for every URI in
 * [setUris] to populate [ShareReceiverUiState.fileNames]. The upload path also
 * calls [stageUri] (which itself calls [safDisplayName] and [copyFromSafUri]).
 * Both are top-level functions in `ShareUriHelpers.kt`; MockK's `mockkStatic` on
 * the generated file façade lets us stub them without a live ContentResolver.
 *
 * The mock file name for `mockkStatic` is the Kotlin file class name:
 * `"app.lusk.virga.share.ShareUriHelpersKt"`.
 *
 * # Why Robolectric?
 * [ShareReceiverViewModel] takes an `@ApplicationContext Context` which is used to
 * resolve `cacheDir`. Robolectric supplies a real [Context] via
 * [androidx.test.core.app.ApplicationProvider].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareReceiverViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    /**
     * All dispatchers backed by the test dispatcher so [advanceUntilIdle]
     * drives the full coroutine graph to completion.
     */
    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
    }

    private val remotesFlow = MutableStateFlow<List<Remote>>(emptyList())
    private val remoteRepository: RemoteRepository = mockk(relaxed = true) {
        every { remotes } returns remotesFlow
    }
    private val fileBrowserRepository: FileBrowserRepository = mockk(relaxed = true)

    // Real application context from Robolectric so Context.cacheDir is valid.
    private val context: Context =
        androidx.test.core.app.ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Mock both helpers at the file façade level so no ContentResolver is needed.
        mockkStatic("app.lusk.virga.share.ShareUriHelpersKt")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic("app.lusk.virga.share.ShareUriHelpersKt")
    }

    private fun viewModel() = ShareReceiverViewModel(
        context = context,
        remoteRepository = remoteRepository,
        fileBrowserRepository = fileBrowserRepository,
        dispatchers = testDispatchers,
    )

    /**
     * Creates a Uri mock that is also stubbed for [safDisplayName] so the
     * ViewModel's `uiState` combine block doesn't hit the ContentResolver.
     */
    private fun stubbedUri(displayName: String): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { safDisplayName(context, uri) } returns displayName
        return uri
    }

    // ── remote list propagation ───────────────────────────────────────────────

    @Test
    fun `should expose an empty remotes list when no remotes are configured`() = runTest(testDispatcher) {
        remotesFlow.value = emptyList()
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        advanceUntilIdle()

        assertThat(vm.uiState.value.remotes).isEmpty()
        job.cancel()
    }

    @Test
    fun `should expose remotes from the repository`() = runTest(testDispatcher) {
        val remote = Remote(name = "gdrive", type = "drive")
        remotesFlow.value = listOf(remote)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        advanceUntilIdle()

        assertThat(vm.uiState.value.remotes).containsExactly(remote)
        job.cancel()
    }

    @Test
    fun `should auto-select the first remote when none is explicitly chosen`() = runTest(testDispatcher) {
        val first = Remote(name = "box", type = "box")
        val second = Remote(name = "dropbox", type = "dropbox")
        remotesFlow.value = listOf(first, second)
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }

        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedRemote).isEqualTo(first)
        job.cancel()
    }

    // ── fileNames derived from setUris + safDisplayName ───────────────────────

    @Test
    fun `should populate fileNames with sanitised display names from uris`() = runTest(testDispatcher) {
        val uri = stubbedUri("photo.jpg")
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setUris(listOf(uri))
        advanceUntilIdle()

        assertThat(vm.uiState.value.fileNames).containsExactly("photo.jpg")
        job.cancel()
    }

    // ── upload guard: no selectedRemote ──────────────────────────────────────

    @Test
    fun `should not call uploadFromLocal when no remote is selected and upload is invoked`() = runTest(testDispatcher) {
        remotesFlow.value = emptyList()
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.upload()
        advanceUntilIdle()

        coVerify(exactly = 0) { fileBrowserRepository.uploadFromLocal(any(), any(), any()) }
        job.cancel()
    }

    @Test
    fun `should leave uploadStatus as Idle when upload is called with no selected remote`() = runTest(testDispatcher) {
        remotesFlow.value = emptyList()
        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.upload()
        advanceUntilIdle()

        assertThat(vm.uiState.value.uploadStatus).isEqualTo(UploadStatus.Idle)
        job.cancel()
    }

    // ── successful upload: all files succeed ──────────────────────────────────

    @Test
    fun `should report succeeded count equal to number of URIs when all uploads succeed`() = runTest(testDispatcher) {
        val remote = Remote(name = "gdrive", type = "drive")
        remotesFlow.value = listOf(remote)
        val uri1 = stubbedUri("file1.jpg")
        val uri2 = stubbedUri("file2.jpg")
        val staged1 = File(context.cacheDir, "file1.jpg").also { it.createNewFile() }
        val staged2 = File(context.cacheDir, "file2.jpg").also { it.createNewFile() }

        every { stageUri(context, uri1, any()) } returns staged1
        every { stageUri(context, uri2, any()) } returns staged2
        coEvery { fileBrowserRepository.uploadFromLocal(any(), any(), any()) } returns Unit

        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setUris(listOf(uri1, uri2))
        vm.upload()
        advanceUntilIdle()

        val status = vm.uiState.value.uploadStatus as UploadStatus.Done
        assertThat(status.succeeded).isEqualTo(2)
        assertThat(status.failed).isEqualTo(0)
        job.cancel()
    }

    @Test
    fun `should call uploadFromLocal for each URI when all uploads succeed`() = runTest(testDispatcher) {
        val remote = Remote(name = "gdrive", type = "drive")
        remotesFlow.value = listOf(remote)
        val uri1 = stubbedUri("a.txt")
        val uri2 = stubbedUri("b.txt")
        val staged1 = File(context.cacheDir, "a.txt").also { it.createNewFile() }
        val staged2 = File(context.cacheDir, "b.txt").also { it.createNewFile() }

        every { stageUri(context, uri1, any()) } returns staged1
        every { stageUri(context, uri2, any()) } returns staged2
        coEvery { fileBrowserRepository.uploadFromLocal(any(), any(), any()) } returns Unit

        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setUris(listOf(uri1, uri2))
        vm.upload()
        advanceUntilIdle()

        coVerify(exactly = 2) { fileBrowserRepository.uploadFromLocal(any(), any(), any()) }
        job.cancel()
    }

    // ── partial failure: one URI fails during upload ──────────────────────────

    @Test
    fun `should report one succeeded and one failed when one upload throws`() = runTest(testDispatcher) {
        val remote = Remote(name = "box", type = "box")
        remotesFlow.value = listOf(remote)
        val uriOk = stubbedUri("ok.png")
        val uriFail = stubbedUri("fail.png")
        val stagedOk = File(context.cacheDir, "ok.png").also { it.createNewFile() }
        val stagedFail = File(context.cacheDir, "fail.png").also { it.createNewFile() }

        every { stageUri(context, uriOk, any()) } returns stagedOk
        every { stageUri(context, uriFail, any()) } returns stagedFail
        coEvery { fileBrowserRepository.uploadFromLocal(stagedOk, any(), any()) } returns Unit
        coEvery { fileBrowserRepository.uploadFromLocal(stagedFail, any(), any()) } throws RuntimeException("network error")

        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setUris(listOf(uriOk, uriFail))
        vm.upload()
        advanceUntilIdle()

        val status = vm.uiState.value.uploadStatus as UploadStatus.Done
        assertThat(status.succeeded).isEqualTo(1)
        assertThat(status.failed).isEqualTo(1)
        job.cancel()
    }

    // ── staging failure: stageUri returns null ─────────────────────────────────

    @Test
    fun `should count a failed file when stageUri returns null`() = runTest(testDispatcher) {
        val remote = Remote(name = "dropbox", type = "dropbox")
        remotesFlow.value = listOf(remote)
        val uri = stubbedUri("bad.txt")

        every { stageUri(context, uri, any()) } returns null

        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setUris(listOf(uri))
        vm.upload()
        advanceUntilIdle()

        val status = vm.uiState.value.uploadStatus as UploadStatus.Done
        assertThat(status.succeeded).isEqualTo(0)
        assertThat(status.failed).isEqualTo(1)
        coVerify(exactly = 0) { fileBrowserRepository.uploadFromLocal(any(), any(), any()) }
        job.cancel()
    }

    // ── path-join correctness ─────────────────────────────────────────────────

    @Test
    fun `should upload to bare filename when destPath is empty`() = runTest(testDispatcher) {
        val remote = Remote(name = "gdrive", type = "drive")
        remotesFlow.value = listOf(remote)
        val uri = stubbedUri("photo.jpg")
        val staged = File(context.cacheDir, "photo.jpg").also { it.createNewFile() }

        every { stageUri(context, uri, any()) } returns staged
        coEvery { fileBrowserRepository.uploadFromLocal(any(), any(), any()) } returns Unit

        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setUris(listOf(uri))
        // destPath left empty (default "")
        vm.upload()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            fileBrowserRepository.uploadFromLocal(staged, "gdrive", "photo.jpg")
        }
        job.cancel()
    }

    @Test
    fun `should prefix filename with destPath when destPath is non-empty`() = runTest(testDispatcher) {
        val remote = Remote(name = "gdrive", type = "drive")
        remotesFlow.value = listOf(remote)
        val uri = stubbedUri("photo.jpg")
        val staged = File(context.cacheDir, "photo.jpg").also { it.createNewFile() }

        every { stageUri(context, uri, any()) } returns staged
        coEvery { fileBrowserRepository.uploadFromLocal(any(), any(), any()) } returns Unit

        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setUris(listOf(uri))
        vm.setDestPath("docs")
        vm.upload()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            fileBrowserRepository.uploadFromLocal(staged, "gdrive", "docs/photo.jpg")
        }
        job.cancel()
    }

    @Test
    fun `should strip trailing slash from destPath before joining`() = runTest(testDispatcher) {
        val remote = Remote(name = "gdrive", type = "drive")
        remotesFlow.value = listOf(remote)
        val uri = stubbedUri("notes.txt")
        val staged = File(context.cacheDir, "notes.txt").also { it.createNewFile() }

        every { stageUri(context, uri, any()) } returns staged
        coEvery { fileBrowserRepository.uploadFromLocal(any(), any(), any()) } returns Unit

        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setUris(listOf(uri))
        vm.setDestPath("docs/")
        vm.upload()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            fileBrowserRepository.uploadFromLocal(staged, "gdrive", "docs/notes.txt")
        }
        job.cancel()
    }

    // ── duplicate-name disambiguation in runUpload ───────────────────────────

    @Test
    fun `should disambiguate remote path when two staged files have the same name`() = runTest(testDispatcher) {
        val remote = Remote(name = "gdrive", type = "drive")
        remotesFlow.value = listOf(remote)
        val uri1 = stubbedUri("photo.jpg")
        val uri2 = stubbedUri("photo.jpg")
        // Both URIs stage to a file named "photo.jpg" — simulating two identical display names.
        val staged1 = File(context.cacheDir, "photo.jpg").also { it.createNewFile() }
        val staged2 = File(context.cacheDir, "photo.jpg")

        every { stageUri(context, uri1, any()) } returns staged1
        every { stageUri(context, uri2, any()) } returns staged2
        coEvery { fileBrowserRepository.uploadFromLocal(any(), any(), any()) } returns Unit

        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setUris(listOf(uri1, uri2))
        vm.upload()
        advanceUntilIdle()

        // First file → "photo.jpg", second → "photo (2).jpg" — no overwrite.
        coVerify(exactly = 1) {
            fileBrowserRepository.uploadFromLocal(staged1, "gdrive", "photo.jpg")
        }
        coVerify(exactly = 1) {
            fileBrowserRepository.uploadFromLocal(staged2, "gdrive", "photo (2).jpg")
        }
        val status = vm.uiState.value.uploadStatus as UploadStatus.Done
        assertThat(status.succeeded).isEqualTo(2)
        assertThat(status.failed).isEqualTo(0)
        job.cancel()
    }

    @Test
    fun `should disambiguate upload fallback names when multiple URIs have no display name`() = runTest(testDispatcher) {
        val remote = Remote(name = "gdrive", type = "drive")
        remotesFlow.value = listOf(remote)
        // Both URIs produce the sanitised fallback "upload" (blank display name).
        val uri1 = stubbedUri("")
        val uri2 = stubbedUri("")
        val stagedUpload = File(context.cacheDir, "upload").also { it.createNewFile() }

        every { stageUri(context, uri1, any()) } returns stagedUpload
        every { stageUri(context, uri2, any()) } returns stagedUpload
        coEvery { fileBrowserRepository.uploadFromLocal(any(), any(), any()) } returns Unit

        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setUris(listOf(uri1, uri2))
        vm.upload()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            fileBrowserRepository.uploadFromLocal(stagedUpload, "gdrive", "upload")
        }
        coVerify(exactly = 1) {
            fileBrowserRepository.uploadFromLocal(stagedUpload, "gdrive", "upload (2)")
        }
        job.cancel()
    }

    // ── selectRemote propagation ──────────────────────────────────────────────

    @Test
    fun `should use the explicitly selected remote name when uploading`() = runTest(testDispatcher) {
        val remote1 = Remote(name = "box", type = "box")
        val remote2 = Remote(name = "gdrive", type = "drive")
        remotesFlow.value = listOf(remote1, remote2)
        val uri = stubbedUri("file.txt")
        val staged = File(context.cacheDir, "file.txt").also { it.createNewFile() }

        every { stageUri(context, uri, any()) } returns staged
        coEvery { fileBrowserRepository.uploadFromLocal(any(), any(), any()) } returns Unit

        val vm = viewModel()
        val job = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setUris(listOf(uri))
        vm.selectRemote(remote2)
        advanceUntilIdle()

        vm.upload()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            fileBrowserRepository.uploadFromLocal(staged, "gdrive", "file.txt")
        }
        job.cancel()
    }
}
