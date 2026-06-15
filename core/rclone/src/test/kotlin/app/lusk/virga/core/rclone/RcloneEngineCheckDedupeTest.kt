package app.lusk.virga.core.rclone

import android.util.Log
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.rclone.api.RcApiClient
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import app.lusk.virga.core.rclone.daemon.RcloneDaemonManager
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Unit tests for [RcloneEngineImpl.check] and [RcloneEngineImpl.dedupe].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RcloneEngineCheckDedupeTest {

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

    private fun setUpDaemon() {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
    }

    // --- check ---

    @Test fun `check dispatches operations_check with srcFs and dstFs`() = runTest(testDispatcher) {
        setUpDaemon()
        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "operations/check", capture(capturedParams)) } returns
            buildJsonObject { put("jobid", 10) }
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", true); put("success", true) }
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns buildJsonObject {
            put("totalTransfers", 5)
            put("errors", 2)
        }

        engine.startDaemon()
        engine.check("/sdcard/DCIM", "gdrive:Backup", SyncOptions(SyncDirection.UPLOAD)).test {
            val result = awaitItem()
            assertThat(result.totalFiles).isEqualTo(5)
            assertThat(result.errors).isEqualTo(2)
            awaitComplete()
        }

        val req = capturedParams.first()
        assertThat(req["srcFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("/sdcard/DCIM")
        assertThat(req["dstFs"]?.jsonPrimitive?.contentOrNull).isEqualTo("gdrive:Backup")
        assertThat(req["_async"]?.jsonPrimitive?.booleanOrNull).isTrue()
    }

    @Test fun `check flow terminates with errors reflecting mismatches`() = runTest(testDispatcher) {
        setUpDaemon()
        coEvery { apiClient.call(any(), any(), any(), "operations/check", any()) } returns
            buildJsonObject { put("jobid", 20) }
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", true); put("success", false); put("error", "3 differences") }
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns buildJsonObject {
            put("errors", 3)
            put("fatalError", false)
        }

        engine.startDaemon()
        engine.check("/sdcard/DCIM", "gdrive:Backup", SyncOptions(SyncDirection.UPLOAD)).test {
            val terminal = awaitItem()
            // Non-fatal errors are tolerated (check never transfers, so partial success is correct).
            assertThat(terminal.errors).isEqualTo(3)
            awaitComplete()
        }
    }

    // --- dedupe ---

    @Test fun `dedupe runs core_command dedupe with remote and dedupe-mode`() = runTest(testDispatcher) {
        setUpDaemon()
        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "core/command", capture(capturedParams)) } returns
            buildJsonObject { put("error", false) }

        val result = engine.dedupe("gdrive")

        assertThat(result.isSuccess).isTrue()
        val req = capturedParams.first()
        assertThat(req["command"]?.jsonPrimitive?.contentOrNull).isEqualTo("dedupe")
        assertThat(req["arg"]!!.jsonArray.first().jsonPrimitive.contentOrNull).isEqualTo("gdrive:")
        assertThat(req["opt"]!!.jsonObject["dedupe-mode"]?.jsonPrimitive?.contentOrNull).isEqualTo("skip")
    }

    @Test fun `dedupe forwards custom dedupe-mode`() = runTest(testDispatcher) {
        setUpDaemon()
        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "core/command", capture(capturedParams)) } returns
            buildJsonObject { put("error", false) }

        engine.dedupe("s3bucket", "newest")

        assertThat(capturedParams.first()["opt"]!!.jsonObject["dedupe-mode"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("newest")
    }

    @Test fun `dedupe returns failure when core_command sets error flag`() = runTest(testDispatcher) {
        setUpDaemon()
        coEvery { apiClient.call(any(), any(), any(), "core/command", any()) } returns
            buildJsonObject { put("error", true); put("result", "command dedupe failed") }

        val result = engine.dedupe("gdrive")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("dedupe failed")
    }

    @Test fun `dedupe returns failure on RC error`() = runTest(testDispatcher) {
        setUpDaemon()
        coEvery { apiClient.call(any(), any(), any(), "core/command", any()) } throws
            app.lusk.virga.core.common.error.VirgaError.Rclone(message = "dedupe not supported")

        val result = engine.dedupe("gdrive")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("dedupe not supported")
    }

    @Test fun `dedupe releases daemon after success`() = runTest(testDispatcher) {
        setUpDaemon()
        coEvery { apiClient.call(any(), any(), any(), "core/command", any()) } returns
            buildJsonObject { put("error", false) }

        engine.dedupe("gdrive")

        // Last leaseholder tears down the daemon and persists config.
        coVerify { daemonManager.stop(fakeDaemon) }
        coVerify { configManager.persistAndCleanup() }
    }

    @Test fun `dedupe releases daemon after failure`() = runTest(testDispatcher) {
        setUpDaemon()
        coEvery { configManager.cleanup() } returns Unit
        coEvery { apiClient.call(any(), any(), any(), "core/command", any()) } throws
            app.lusk.virga.core.common.error.VirgaError.Rclone(message = "failed")

        engine.dedupe("gdrive")

        // Daemon is still released even on failure.
        coVerify { daemonManager.stop(fakeDaemon) }
    }
}
