package app.lusk.virga.core.rclone

import android.util.Log
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.rclone.api.RcApiClient
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import app.lusk.virga.core.rclone.daemon.RcloneDaemonManager
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Unit tests for [RcloneEngineImpl.downloadFile] and [RcloneEngineImpl.uploadFile].
 * Key assertion: the local side must have NO colon in srcFs/dstFs because local
 * absolute paths contain no colon — [splitFs] would corrupt them otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RcloneEngineDownloadUploadTest {

    private val daemonManager = mockk<RcloneDaemonManager>()
    private val configManager = mockk<RcloneConfigManager>()
    private val apiClient = mockk<RcApiClient>()
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main get() = testDispatcher
        override val default get() = testDispatcher
        override val io get() = testDispatcher
    }

    private lateinit var engine: RcloneEngineImpl
    private lateinit var fakeDaemon: RcloneDaemon

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        coEvery { daemonManager.stop(any()) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit
        engine = RcloneEngineImpl(daemonManager, configManager, apiClient, dispatchers)
        fakeDaemon = RcloneDaemon(
            process = mockk { every { isAlive } returns true },
            port = 9999,
            user = "u",
            pass = "p",
        )
    }

    private fun stubDaemon() {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
    }

    // -------------------------------------------------------------------------
    // downloadFile
    // -------------------------------------------------------------------------

    @Test
    fun `downloadFile uses remote colon as srcFs and bare local dir as dstFs`() = runTest(testDispatcher) {
        stubDaemon()
        val captured = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "operations/copyfile", capture(captured)) } returns buildJsonObject {}

        engine.startDaemon()
        engine.downloadFile("gdrive", "Photos/img.jpg", "/data/cache/shared", "img.jpg")

        val req = captured.first()
        assertThat(req["srcFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("gdrive:")
        assertThat(req["srcRemote"]?.jsonPrimitive?.contentOrNull).isEqualTo("Photos/img.jpg")
        // Local dir must have NO colon — it is an absolute path, not a remote spec.
        assertThat(req["dstFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("/data/cache/shared")
        assertThat(req["dstFs"]?.jsonPrimitive?.contentOrNull).doesNotContain(":")
        assertThat(req["dstRemote"]?.jsonPrimitive?.contentOrNull).isEqualTo("img.jpg")
    }

    @Test
    fun `downloadFile propagates VirgaError on RC failure`() = runTest(testDispatcher) {
        stubDaemon()
        coEvery { apiClient.call(any(), any(), any(), "operations/copyfile", any()) } throws
            VirgaError.Rclone(message = "quota exceeded")

        engine.startDaemon()
        val error = assertThrows<VirgaError.Rclone> {
            engine.downloadFile("gdrive", "file.txt", "/tmp/shared", "file.txt")
        }
        assertThat(error.message).contains("quota exceeded")
    }

    @Test
    fun `downloadFile constructs srcFs with trailing colon from remoteName`() = runTest(testDispatcher) {
        stubDaemon()
        val captured = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "operations/copyfile", capture(captured)) } returns buildJsonObject {}

        engine.startDaemon()
        engine.downloadFile("s3bucket", "2024/report.pdf", "/data/downloads", "report.pdf")

        val req = captured.first()
        assertThat(req["srcFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("s3bucket:")
    }

    // -------------------------------------------------------------------------
    // uploadFile
    // -------------------------------------------------------------------------

    @Test
    fun `uploadFile uses bare local dir as srcFs and remote colon as dstFs`() = runTest(testDispatcher) {
        stubDaemon()
        val captured = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "operations/copyfile", capture(captured)) } returns buildJsonObject {}

        engine.startDaemon()
        engine.uploadFile("/data/cache/shared", "upload.zip", "gdrive", "Uploads/upload.zip")

        val req = captured.first()
        // Local side must have NO colon.
        assertThat(req["srcFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("/data/cache/shared")
        assertThat(req["srcFs"]?.jsonPrimitive?.contentOrNull).doesNotContain(":")
        assertThat(req["srcRemote"]?.jsonPrimitive?.contentOrNull).isEqualTo("upload.zip")
        assertThat(req["dstFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("gdrive:")
        assertThat(req["dstRemote"]?.jsonPrimitive?.contentOrNull).isEqualTo("Uploads/upload.zip")
    }

    @Test
    fun `uploadFile propagates VirgaError on RC failure`() = runTest(testDispatcher) {
        stubDaemon()
        coEvery { apiClient.call(any(), any(), any(), "operations/copyfile", any()) } throws
            VirgaError.Rclone(message = "storage full")

        engine.startDaemon()
        val error = assertThrows<VirgaError.Rclone> {
            engine.uploadFile("/tmp/shared", "file.bin", "s3", "folder/file.bin")
        }
        assertThat(error.message).contains("storage full")
    }

    @Test
    fun `uploadFile constructs dstFs with trailing colon from remoteName`() = runTest(testDispatcher) {
        stubDaemon()
        val captured = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "operations/copyfile", capture(captured)) } returns buildJsonObject {}

        engine.startDaemon()
        engine.uploadFile("/tmp/staging", "notes.txt", "dropbox", "docs/notes.txt")

        val req = captured.first()
        assertThat(req["dstFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("dropbox:")
    }
}
