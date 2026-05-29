package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Unit tests for [RcloneEngineImpl]. All external collaborators are mocked so
 * no real rclone process or HTTP server is involved.
 *
 * Note: add `testImplementation("io.mockk:mockk:<version>")` to the rclone
 * module's build.gradle.kts if not already present.
 */
class RcloneEngineImplTest {

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
        engine = RcloneEngineImpl(daemonManager, configManager, apiClient, dispatchers)
        fakeDaemon = RcloneDaemon(
            process = mockk { every { isAlive } returns true },
            port = 9999,
            user = "u",
            pass = "p",
        )
    }

    // --- startDaemon ---

    @Test fun `startDaemon starts a new daemon when none is running`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon

        val result = engine.startDaemon()

        assertThat(result).isSameInstanceAs(fakeDaemon)
        coVerify(exactly = 1) { daemonManager.start(any()) }
    }

    @Test fun `startDaemon reuses alive daemon without restarting`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true

        engine.startDaemon()
        engine.startDaemon() // second call

        coVerify(exactly = 1) { daemonManager.start(any()) }
    }

    // --- stopDaemon ---

    @Test fun `stopDaemon stops the running daemon and persists config`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit

        engine.startDaemon()
        engine.stopDaemon()

        coVerify { daemonManager.stop(fakeDaemon) }
        coVerify { configManager.persistAndCleanup() }
    }

    // --- isDaemonHealthy ---

    @Test fun `isDaemonHealthy returns false when daemon is null`() = runTest(testDispatcher) {
        assertThat(engine.isDaemonHealthy()).isFalse()
    }

    @Test fun `isDaemonHealthy returns false when process is dead`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns false

        engine.startDaemon()
        assertThat(engine.isDaemonHealthy()).isFalse()
    }

    @Test fun `isDaemonHealthy returns true when rc_noop succeeds`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "rc/noop", any()) } returns buildJsonObject {}

        engine.startDaemon()
        assertThat(engine.isDaemonHealthy()).isTrue()
    }

    // --- listRemotes / getConfig ---

    @Test fun `listRemotes returns Remote list parsed from config_dump`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "config/dump", any()) } returns buildJsonObject {
            put("gdrive", buildJsonObject { put("type", "drive") })
            put("s3bucket", buildJsonObject { put("type", "s3") })
        }

        engine.startDaemon()
        val remotes = engine.listRemotes()

        assertThat(remotes).hasSize(2)
        assertThat(remotes.map { it.name }).containsExactly("gdrive", "s3bucket")
        assertThat(remotes.map { it.type }).containsExactly("drive", "s3")
    }

    @Test fun `getConfig returns unknown type when type key missing`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "config/dump", any()) } returns buildJsonObject {
            put("weirdremote", buildJsonObject {}) // no "type" key
        }

        engine.startDaemon()
        val config = engine.getConfig()

        assertThat(config.remotes["weirdremote"]).isEqualTo("unknown")
    }

    // --- createRemote / deleteRemote ---

    @Test fun `createRemote calls config_create then persists config`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "config/create", any()) } returns buildJsonObject {}
        coEvery { configManager.persistAndCleanup() } returns Unit

        val result = engine.createRemote("newdrive", "drive", mapOf("client_id" to "abc"))

        assertThat(result.isSuccess).isTrue()
        coVerify { apiClient.call(any(), any(), any(), "config/create", any()) }
        coVerify { configManager.persistAndCleanup() }
    }

    @Test fun `createRemote returns failure when api throws VirgaError`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "config/create", any()) } throws
            VirgaError.Rclone(exitCode = 400, message = "already exists")

        val result = engine.createRemote("dupe", "drive", emptyMap())

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(VirgaError.Rclone::class.java)
    }

    // --- listDir ---

    @Test fun `listDir maps operations_list response to FileItem list`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "operations/list", any()) } returns buildJsonObject {
            put("list", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("Name", "photo.jpg")
                    put("Path", "photos/photo.jpg")
                    put("IsDir", false)
                    put("Size", 1024L)
                    put("MimeType", "image/jpeg")
                })
                add(buildJsonObject {
                    put("Name", "albums")
                    put("Path", "albums")
                    put("IsDir", true)
                    put("Size", 0L)
                })
            })
        }

        engine.startDaemon()
        val files = engine.listDir("gdrive:", "photos")

        assertThat(files).hasSize(2)
        assertThat(files[0].name).isEqualTo("photo.jpg")
        assertThat(files[0].isDir).isFalse()
        assertThat(files[0].size).isEqualTo(1024L)
        assertThat(files[1].isDir).isTrue()
    }

    @Test fun `listDir returns empty list when list key absent`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "operations/list", any()) } returns buildJsonObject {}

        engine.startDaemon()
        val files = engine.listDir("gdrive:", "empty")

        assertThat(files).isEmpty()
    }

    // --- sync job flow ---

    @Test fun `sync emits SyncProgress and completes on successful job`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true

        coEvery { apiClient.call(any(), any(), any(), "sync/sync", any()) } returns
            buildJsonObject { put("jobid", 42) }

        val statsResponse = buildJsonObject {
            put("bytes", 500L)
            put("totalBytes", 1000L)
            put("speed", 100.0)
            put("transfers", 2)
            put("totalTransfers", 4)
        }
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns statsResponse

        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", true); put("success", true) }

        engine.startDaemon()

        // job/status is polled first; job is already finished so only the final
        // core/stats snapshot is emitted before complete (one emission total).
        val options = SyncOptions(direction = SyncDirection.UPLOAD)
        engine.sync("local:/sdcard/photos", "gdrive:photos", options).test {
            val final = awaitItem()
            assertThat(final.bytesTransferred).isEqualTo(500L)
            assertThat(final.totalBytes).isEqualTo(1000L)
            awaitComplete()
        }
    }

    @Test fun `sync throws VirgaError_Rclone when job fails`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "sync/sync", any()) } returns
            buildJsonObject { put("jobid", 7) }
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns buildJsonObject {}
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", true); put("success", false); put("error", "permission denied") }

        engine.startDaemon()

        // job/status is polled first and sees failure immediately; no stats emitted.
        engine.sync("local:/", "gdrive:", SyncOptions(SyncDirection.UPLOAD)).test {
            val err = awaitError()
            assertThat(err).isInstanceOf(VirgaError.Rclone::class.java)
            assertThat(err.message).contains("permission denied")
        }
    }

    @Test fun `sync throws when rclone returns no jobid`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "sync/sync", any()) } returns buildJsonObject {}

        engine.startDaemon()

        engine.sync("local:/", "gdrive:", SyncOptions(SyncDirection.UPLOAD)).test {
            awaitError().also { assertThat(it).isInstanceOf(VirgaError.Rclone::class.java) }
        }
    }

    // --- importConfig ---

    @Test fun `importConfig stops daemon and delegates to configManager`() = runTest(testDispatcher) {
        coEvery { daemonManager.stop(any()) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit
        coEvery { configManager.import(any()) } returns Unit

        val result = engine.importConfig("[gdrive]\ntype=drive\n")

        assertThat(result.isSuccess).isTrue()
        coVerify { configManager.import("[gdrive]\ntype=drive\n") }
    }
}
