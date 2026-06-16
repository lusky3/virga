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
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Unit tests for [RcloneEngineImpl.copyFile] and [RcloneEngineImpl.purge].
 *
 * Mirrors the patterns established in [RcloneEngineImplTest] and
 * [RcloneEngineCheckDedupeTest]: all collaborators are mocked; no real rclone
 * process or HTTP server is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RcloneEngineFileOpsTest {

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

    @BeforeEach fun setUp() {
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
    // copyFile
    // -------------------------------------------------------------------------

    @Test fun `copyFile splits source and dest into fs and remote params`() = runTest(testDispatcher) {
        stubDaemon()
        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery {
            apiClient.call(any(), any(), any(), "operations/copyfile", capture(capturedParams))
        } returns buildJsonObject {}

        engine.startDaemon()
        engine.copyFile("gdrive:Photos/img.jpg", "gdrive:Backup/img.jpg")

        val req = capturedParams.first()
        assertThat(req["srcFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("gdrive:")
        assertThat(req["srcRemote"]?.jsonPrimitive?.contentOrNull).isEqualTo("Photos/img.jpg")
        assertThat(req["dstFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("gdrive:")
        assertThat(req["dstRemote"]?.jsonPrimitive?.contentOrNull).isEqualTo("Backup/img.jpg")
    }

    @Test fun `copyFile supports cross-remote copy`() = runTest(testDispatcher) {
        stubDaemon()
        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery {
            apiClient.call(any(), any(), any(), "operations/copyfile", capture(capturedParams))
        } returns buildJsonObject {}

        engine.startDaemon()
        engine.copyFile("gdrive:Photos/img.jpg", "s3:bucket/img.jpg")

        val req = capturedParams.first()
        assertThat(req["srcFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("gdrive:")
        assertThat(req["dstFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("s3:")
        assertThat(req["dstRemote"]?.jsonPrimitive?.contentOrNull).isEqualTo("bucket/img.jpg")
    }

    @Test fun `copyFile propagates VirgaError on RC failure`() = runTest(testDispatcher) {
        stubDaemon()
        coEvery {
            apiClient.call(any(), any(), any(), "operations/copyfile", any())
        } throws VirgaError.Rclone(exitCode = 403, message = "permission denied")

        engine.startDaemon()
        val error = assertThrows<VirgaError.Rclone> {
            engine.copyFile("gdrive:file.txt", "gdrive:other.txt")
        }
        assertThat(error.message).contains("permission denied")
    }

    // -------------------------------------------------------------------------
    // purge
    // -------------------------------------------------------------------------

    @Test fun `purge issues operations_purge with fs and remote params`() = runTest(testDispatcher) {
        stubDaemon()
        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery {
            apiClient.call(any(), any(), any(), "operations/purge", capture(capturedParams))
        } returns buildJsonObject {}

        engine.startDaemon()
        engine.purge("gdrive:", "OldPhotos")

        val req = capturedParams.first()
        assertThat(req["fs"]?.jsonPrimitive?.contentOrNull).isEqualTo("gdrive:")
        assertThat(req["remote"]?.jsonPrimitive?.contentOrNull).isEqualTo("OldPhotos")
    }

    @Test fun `purge propagates VirgaError on RC failure`() = runTest(testDispatcher) {
        stubDaemon()
        coEvery {
            apiClient.call(any(), any(), any(), "operations/purge", any())
        } throws VirgaError.Rclone(message = "directory not found")

        engine.startDaemon()
        val error = assertThrows<VirgaError.Rclone> {
            engine.purge("gdrive:", "NonExistentDir")
        }
        assertThat(error.message).contains("directory not found")
    }
}
