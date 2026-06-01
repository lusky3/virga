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
        // mutatingConfig tears down the daemon (releasing the plaintext config) before persisting.
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit

        engine.createRemote("newdrive", "drive", mapOf("client_id" to "abc"))

        coVerify { apiClient.call(any(), any(), any(), "config/create", any()) }
        coVerify { daemonManager.stop(fakeDaemon) }
        coVerify { configManager.persistAndCleanup() }
    }

    @Test fun `createRemote throws VirgaError when api throws and discards plaintext config`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "config/create", any()) } throws
            VirgaError.Rclone(exitCode = 400, message = "already exists")
        // On failure mutatingConfig tears down the daemon and discards the plaintext config.
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.cleanup() } returns Unit

        val error = assertThrows<VirgaError.Rclone> {
            engine.createRemote("dupe", "drive", emptyMap())
        }

        assertThat(error.message).contains("already exists")
        coVerify { configManager.cleanup() }
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

        coEvery { apiClient.call(any(), any(), any(), "sync/copy", any()) } returns
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
        coEvery { apiClient.call(any(), any(), any(), "sync/copy", any()) } returns
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
        coEvery { apiClient.call(any(), any(), any(), "sync/copy", any()) } returns buildJsonObject {}

        engine.startDaemon()

        engine.sync("local:/", "gdrive:", SyncOptions(SyncDirection.UPLOAD)).test {
            awaitError().also { assertThat(it).isInstanceOf(VirgaError.Rclone::class.java) }
        }
    }

    @Test fun `sync uses sync_copy (additive) by default and sync_sync only when deleteExtraneous`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns buildJsonObject {}
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", true); put("success", true) }
        // Default options -> additive copy (must NOT mirror-delete).
        coEvery { apiClient.call(any(), any(), any(), "sync/copy", any()) } returns buildJsonObject { put("jobid", 1) }
        // Explicit deleteExtraneous -> destructive mirror.
        coEvery { apiClient.call(any(), any(), any(), "sync/sync", any()) } returns buildJsonObject { put("jobid", 2) }

        engine.startDaemon()
        engine.sync("local:/x", "gdrive:x", SyncOptions(SyncDirection.UPLOAD)).test { awaitItem(); awaitComplete() }
        engine.sync("local:/x", "gdrive:x", SyncOptions(SyncDirection.UPLOAD, deleteExtraneous = true)).test { awaitItem(); awaitComplete() }

        coVerify { apiClient.call(any(), any(), any(), "sync/copy", any()) }
        coVerify { apiClient.call(any(), any(), any(), "sync/sync", any()) }
    }

    // --- providers ---

    @Test fun `providers returns parsed RemoteProvider list`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "config/providers", any()) } returns buildJsonObject {
            put("providers", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("Name", "drive")
                    put("Description", "Google Drive")
                    put("Options", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("Name", "client_id")
                            put("Help", "OAuth Client ID")
                            put("Type", "string")
                            put("Required", false)
                            put("IsPassword", false)
                            put("Advanced", false)
                            put("Examples", kotlinx.serialization.json.buildJsonArray {})
                        })
                        add(buildJsonObject {
                            put("Name", "client_secret")
                            put("Help", "OAuth Client Secret")
                            put("Type", "string")
                            put("Required", false)
                            put("IsPassword", true)
                            put("Advanced", false)
                            put("Examples", kotlinx.serialization.json.buildJsonArray {})
                        })
                    })
                })
            })
        }

        engine.startDaemon()
        val result = engine.providers()

        assertThat(result).hasSize(1)
        val drive = result[0]
        assertThat(drive.name).isEqualTo("drive")
        assertThat(drive.description).isEqualTo("Google Drive")
        assertThat(drive.options).hasSize(2)
        assertThat(drive.options[0].name).isEqualTo("client_id")
        assertThat(drive.options[0].isPassword).isFalse()
        assertThat(drive.options[1].name).isEqualTo("client_secret")
        assertThat(drive.options[1].isPassword).isTrue()
    }

    @Test fun `providers returns empty list when api call fails`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "config/providers", any()) } throws
            VirgaError.Rclone(message = "not supported")

        engine.startDaemon()
        val result = engine.providers()

        assertThat(result).isEmpty()
    }

    @Test fun `providers returns empty list when providers key is absent`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "config/providers", any()) } returns buildJsonObject {}

        engine.startDaemon()
        val result = engine.providers()

        assertThat(result).isEmpty()
    }

    @Test fun `providers parses Examples into pairs`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "config/providers", any()) } returns buildJsonObject {
            put("providers", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("Name", "s3")
                    put("Description", "Amazon S3")
                    put("Options", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("Name", "provider")
                            put("Help", "S3 provider")
                            put("Type", "string")
                            put("Required", false)
                            put("IsPassword", false)
                            put("Advanced", false)
                            put("Examples", kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject { put("Value", "AWS"); put("Help", "Amazon Web Services") })
                                add(buildJsonObject { put("Value", "Minio"); put("Help", "Minio object storage") })
                            })
                        })
                    })
                })
            })
        }

        engine.startDaemon()
        val result = engine.providers()

        val providerOpt = result[0].options[0]
        assertThat(providerOpt.examples).hasSize(2)
        assertThat(providerOpt.examples[0].first).isEqualTo("AWS")
        assertThat(providerOpt.examples[0].second).isEqualTo("Amazon Web Services")
    }

    // --- importConfig ---

    @Test fun `importConfig stops daemon and delegates to configManager`() = runTest(testDispatcher) {
        coEvery { daemonManager.stop(any()) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit
        coEvery { configManager.import(any()) } returns Unit

        engine.importConfig("[gdrive]\ntype=drive\n")

        coVerify { configManager.import("[gdrive]\ntype=drive\n") }
    }
}
