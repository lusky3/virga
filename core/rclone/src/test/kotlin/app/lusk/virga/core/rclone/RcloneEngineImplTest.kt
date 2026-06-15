package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.rclone.api.RcApiClient
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import app.lusk.virga.core.rclone.daemon.RcloneDaemonManager
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle (cancellation-safe teardown tests)
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
        // android.util.Log is not available on the plain JVM test classpath; stub the
        // overloads the engine uses (providers()/importConfig() log on failure).
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        // One-shot ops now run under a refcount lease (rclone-M2/M3): an isolated call
        // acquires the daemon then releases it, and the last release tears the daemon down
        // (daemonManager.stop) + re-encrypts the config (persistAndCleanup). Default-stub
        // both so single-call ops don't trip the strict mock; tests that assert specific
        // teardown behavior override these.
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

    // --- cancellation-safe teardown (NonCancellable) ---
    //
    // These prove rclone-H1: a teardown whose calling job is cancelled MID-FLIGHT must
    // still stop the rclone process and persist/discard the plaintext config. Each stubs
    // daemonManager.stop with a virtual `delay`, launches the teardown UNDISPATCHED so it
    // reaches that suspension, then cancels the job before advancing the clock. Under the
    // old `withContext(dispatchers.io)` the post-stop work (persist/cleanup) would be
    // skipped once the job was cancelled; under `withContext(NonCancellable)` it runs.

    @Test fun `stopDaemon teardown completes after the calling scope is cancelled`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } coAnswers { delay(1_000) }
        coEvery { configManager.persistAndCleanup() } returns Unit

        engine.startDaemon()

        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.stopDaemon() }
        job.cancel()                 // cancelled while parked inside stop()'s delay
        advanceUntilIdle()           // NonCancellable lets the delay + persist finish
        job.join()

        coVerify { daemonManager.stop(fakeDaemon) }
        coVerify { configManager.persistAndCleanup() } // post-stop work survived cancellation
    }

    @Test fun `releaseDaemon teardown completes after the calling scope is cancelled`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } coAnswers { delay(1_000) }
        coEvery { configManager.persistAndCleanup() } returns Unit

        engine.acquireDaemon() // leases = 1; last release must tear down

        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.releaseDaemon() }
        job.cancel()
        advanceUntilIdle()
        job.join()

        // Daemon torn down + plaintext re-encrypted despite cancellation: no surviving
        // process, no lease leak.
        coVerify { daemonManager.stop(fakeDaemon) }
        coVerify { configManager.persistAndCleanup() }
    }

    @Test fun `mutatingConfig teardown completes after the calling scope is cancelled`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } coAnswers { delay(1_000) }
        coEvery { configManager.persistAndCleanup() } returns Unit
        coEvery { apiClient.call(any(), any(), any(), "config/create", any()) } returns buildJsonObject {}

        // User backs out mid-createRemote: the calling scope is cancelled, but the finally
        // teardown (NonCancellable) must still stop the daemon + persist the config.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.createRemote("newdrive", "drive", mapOf("client_id" to "abc"))
        }
        job.cancel()
        advanceUntilIdle()
        job.join()

        coVerify { daemonManager.stop(fakeDaemon) }
        coVerify { configManager.persistAndCleanup() }
    }

    // --- lease (acquire/release) reference counting ---

    @Test fun `daemon survives until the last lease is released`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit

        // Two concurrent consumers (e.g. two SyncWorkers) lease the shared daemon.
        engine.acquireDaemon()
        engine.acquireDaemon()

        // First release must NOT stop the daemon the second consumer is still using.
        engine.releaseDaemon()
        coVerify(exactly = 0) { daemonManager.stop(fakeDaemon) }

        // Last release tears it down exactly once.
        engine.releaseDaemon()
        coVerify(exactly = 1) { daemonManager.stop(fakeDaemon) }
    }

    @Test fun `stopDaemonIfIdle does not stop a leased daemon`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit

        engine.acquireDaemon()          // a sync holds a lease
        engine.stopDaemonIfIdle()       // browser closing — must be a no-op
        coVerify(exactly = 0) { daemonManager.stop(fakeDaemon) }

        engine.releaseDaemon()          // sync done -> now it stops
        coVerify(exactly = 1) { daemonManager.stop(fakeDaemon) }
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

    @Test fun `createRemote sets opt obscure when sensitiveKeys present`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit

        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "config/create", capture(capturedParams)) } returns buildJsonObject {}

        engine.createRemote("box", "box", mapOf("token" to "t", "pass" to "secret"), sensitiveKeys = setOf("pass"))

        val opt = capturedParams.first()["opt"]?.jsonObject
        assertThat(opt?.get("obscure")?.jsonPrimitive?.booleanOrNull).isTrue()
        assertThat(opt?.get("nonInteractive")?.jsonPrimitive?.booleanOrNull).isTrue()
    }

    @Test fun `createRemote omits opt obscure for token-only OAuth create`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit

        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "config/create", capture(capturedParams)) } returns buildJsonObject {}

        engine.createRemote("gdrive", "drive", mapOf("token" to "{\"access_token\":\"x\"}"))

        val opt = capturedParams.first()["opt"]?.jsonObject
        assertThat(opt?.containsKey("obscure")).isFalse()
        assertThat(opt?.get("nonInteractive")?.jsonPrimitive?.booleanOrNull).isTrue()
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

    // --- mkdir ---

    @Test fun `mkdir issues operations_mkdir with fs and remote params`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true

        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "operations/mkdir", capture(capturedParams)) } returns buildJsonObject {}

        engine.startDaemon()
        engine.mkdir("gdrive:", "Photos/2024")

        val req = capturedParams.first()
        assertThat(req["fs"]?.jsonPrimitive?.contentOrNull).isEqualTo("gdrive:")
        assertThat(req["remote"]?.jsonPrimitive?.contentOrNull).isEqualTo("Photos/2024")
    }

    @Test fun `mkdir propagates VirgaError on RC failure`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "operations/mkdir", any()) } throws
            VirgaError.Rclone(exitCode = 403, message = "permission denied")

        engine.startDaemon()
        val error = assertThrows<VirgaError.Rclone> { engine.mkdir("gdrive:", "blocked") }

        assertThat(error.message).contains("permission denied")
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
        // fatalError=true: a genuine abort (not a tolerable file-level error), so even a
        // COPY must throw rather than treat it as a partial success.
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns
            buildJsonObject { put("fatalError", true) }
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

    @Test fun `sync uses sync_move when deleteSource is true`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns buildJsonObject {}
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", true); put("success", true) }
        coEvery { apiClient.call(any(), any(), any(), "sync/move", any()) } returns buildJsonObject { put("jobid", 3) }

        engine.startDaemon()
        engine.sync("local:/x", "gdrive:x", SyncOptions(SyncDirection.UPLOAD, deleteSource = true)).test {
            awaitItem(); awaitComplete()
        }

        coVerify { apiClient.call(any(), any(), any(), "sync/move", any()) }
    }

    @Test fun `sync rejects both deleteSource and deleteExtraneous (mutually exclusive)`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true

        engine.startDaemon()
        engine.sync(
            "local:/x",
            "gdrive:x",
            SyncOptions(SyncDirection.UPLOAD, deleteSource = true, deleteExtraneous = true),
        ).test {
            awaitError().also { assertThat(it).isInstanceOf(VirgaError.Rclone::class.java) }
        }

        // Fail-fast: no destructive command is dispatched when the flags conflict.
        coVerify(exactly = 0) { apiClient.call(any(), any(), any(), "sync/move", any()) }
        coVerify(exactly = 0) { apiClient.call(any(), any(), any(), "sync/sync", any()) }
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

    // --- partial-success tolerance: file-level errors don't fail a COPY/backup ---
    //
    // rclone reports success:false whenever ANY file errored, even though it copied the
    // rest and continued. A one-way COPY (deleteExtraneous=false) must treat a non-fatal
    // file error as a PARTIAL SUCCESS: emit the terminal stats (errors=N) and complete,
    // not throw. A fatal abort (fatalError=true) or a delete MIRROR still fails hard.

    @Test fun `sync copy with file-level errors completes as partial success`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "sync/copy", any()) } returns
            buildJsonObject { put("jobid", 11) }
        // Finished but not successful: rclone hit file-level errors but no fatal abort.
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", true); put("success", false); put("error", "1 error(s) reading") }
        // core/stats reports fatalError=false and a non-zero error COUNT.
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns buildJsonObject {
            put("bytes", 300L)
            put("transfers", 3)
            put("errors", 2)
            put("fatalError", false)
        }

        engine.startDaemon()

        // deleteExtraneous=false (COPY) tolerates file errors: terminal SyncProgress(errors=2),
        // then complete — no thrown error.
        engine.sync("local:/x", "gdrive:x", SyncOptions(SyncDirection.UPLOAD)).test {
            val final = awaitItem()
            assertThat(final.errors).isEqualTo(2)
            assertThat(final.transferredFiles).isEqualTo(3)
            awaitComplete()
        }
    }

    @Test fun `sync copy with a fatal error still throws`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "sync/copy", any()) } returns
            buildJsonObject { put("jobid", 12) }
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", true); put("success", false); put("error", "couldn't connect: auth failed") }
        // fatalError=true: an auth/connection abort, not a tolerable file error.
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns buildJsonObject {
            put("errors", 1)
            put("fatalError", true)
        }

        engine.startDaemon()

        engine.sync("local:/x", "gdrive:x", SyncOptions(SyncDirection.UPLOAD)).test {
            val err = awaitError()
            assertThat(err).isInstanceOf(VirgaError::class.java)
            assertThat(err.message).contains("auth failed")
        }
    }

    @Test fun `sync mirror with file-level errors still throws even when not fatal`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "sync/sync", any()) } returns
            buildJsonObject { put("jobid", 13) }
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", true); put("success", false); put("error", "1 error reading source") }
        // Non-fatal file error, but a delete MIRROR must keep failing hard: proceeding could
        // delete the cloud counterparts of source files it couldn't read.
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns buildJsonObject {
            put("errors", 1)
            put("fatalError", false)
        }

        engine.startDaemon()

        engine.sync("local:/x", "gdrive:x", SyncOptions(SyncDirection.UPLOAD, deleteExtraneous = true)).test {
            val err = awaitError()
            assertThat(err).isInstanceOf(VirgaError::class.java)
            assertThat(err.message).contains("error reading source")
        }
    }

    @Test fun `sync copy throws the job error (not the stats error) when core_stats fails`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "sync/copy", any()) } returns
            buildJsonObject { put("jobid", 14) }
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", true); put("success", false); put("error", "1 error(s) reading") }
        // The fatalError probe fails: we can't confirm the failure is non-fatal, so the
        // run must surface the ORIGINAL job error — not the stats-fetch error, and not a crash.
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } throws
            VirgaError.Network("daemon unreachable for stats")

        engine.startDaemon()

        engine.sync("local:/x", "gdrive:x", SyncOptions(SyncDirection.UPLOAD)).test {
            val err = awaitError()
            assertThat(err).isInstanceOf(VirgaError::class.java)
            assertThat(err.message).contains("reading")
            assertThat(err.message).doesNotContain("stats")
        }
    }

    // --- job control: collector cancellation aborts the async job (rclone-M4) ---

    @Test fun `cancelling the sync collector mid-poll issues job_stop`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "sync/copy", any()) } returns
            buildJsonObject { put("jobid", 99) }
        // Job never finishes: every poll reports running, so the collector keeps looping
        // until we cancel it. Stats advance each tick so the stall guard never fires.
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", false) }
        var bytes = 0L
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } answers {
            bytes += 100L
            buildJsonObject { put("bytes", bytes) }
        }
        coEvery { apiClient.call(any(), any(), any(), "job/stop", any()) } returns buildJsonObject {}

        engine.startDaemon()

        // Collect in a child job, let it poll a couple of ticks, then cancel it. The flow's
        // finally must abort the still-running async job via job/stop (NonCancellable).
        val collector = launch {
            engine.sync("local:/x", "gdrive:x", SyncOptions(SyncDirection.UPLOAD)).collect { }
        }
        advanceTimeBy(1_600) // > 2 * POLL_INTERVAL_MS (750ms): let the loop poll a couple of ticks
        collector.cancel()
        advanceUntilIdle()

        coVerify { apiClient.call(any(), any(), any(), "job/stop", any()) }
    }

    // --- one-shot lease lifecycle (rclone-M2/M3) ---

    @Test fun `isolated one-shot op starts then stops the daemon`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "operations/list", any()) } returns buildJsonObject {}

        // No prior startDaemon(): the op itself must start the daemon, then release the lease
        // (leases 1->0) tears it down + re-encrypts the config.
        engine.listDir("gdrive:", "photos")

        coVerify(exactly = 1) { daemonManager.start(any()) }
        coVerify(exactly = 1) { daemonManager.stop(fakeDaemon) }
        coVerify(exactly = 1) { configManager.persistAndCleanup() }
    }

    @Test fun `one-shot op made while a lease is held does not stop the daemon`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "operations/about", any()) } returns buildJsonObject {}

        engine.acquireDaemon() // an outer consumer (e.g. a SyncWorker) holds a lease

        // The op's lease just nests (leases 2->1 on release): the daemon must survive.
        engine.about("gdrive")
        coVerify(exactly = 0) { daemonManager.stop(fakeDaemon) }

        // Only the outer consumer's final release tears it down.
        engine.releaseDaemon()
        coVerify(exactly = 1) { daemonManager.stop(fakeDaemon) }
    }

    // --- stall guard (rclone-M4) ---
    //
    // The stall guard at runJobWithProgress compares against System.currentTimeMillis()
    // (wall clock), NOT the test scheduler's virtual time, so advanceTimeBy() cannot
    // deterministically drive it past STALL_TIMEOUT_MS without a real 120s sleep. We
    // therefore assert the guard's KEY invariant that IS unit-testable on virtual time:
    // a long check-only phase (no bytes/transfers/deletes, only `checks` rising) must NOT
    // be mistaken for a stall — the job runs to completion. Tracking `checks` in the
    // progress sentinel is exactly what prevents rclone's listing/compare phases (which
    // move zero bytes) from tripping the abort.
    @Test fun `a check-only phase does not trip the stall guard`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "sync/copy", any()) } returns
            buildJsonObject { put("jobid", 5) }

        // First three polls: running, zero bytes but `checks` climbing (compare phase).
        // Fourth poll: finished+success. The climbing `checks` keeps lastProgressAtMs fresh,
        // so the guard never fires and the job completes normally.
        var poll = 0
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } answers {
            poll++
            buildJsonObject { put("finished", poll >= 4); if (poll >= 4) put("success", true) }
        }
        var checks = 0
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } answers {
            checks += 10
            buildJsonObject { put("bytes", 0L); put("checks", checks) }
        }

        engine.startDaemon()

        engine.sync("local:/x", "gdrive:x", SyncOptions(SyncDirection.UPLOAD)).test {
            // Three running emissions (check-only) then the terminal one, no stall error.
            awaitItem(); awaitItem(); awaitItem(); awaitItem()
            awaitComplete()
        }
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

    // --- createCryptRemote ---

    @Test fun `createCryptRemote sends config_create with type=crypt and obscure=true`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit

        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "config/create", capture(capturedParams)) } returns buildJsonObject {}

        engine.createCryptRemote("myenc", "gdrive:encrypted", "s3cr3t", null)

        val req = capturedParams.first()
        assertThat(req["name"]?.jsonPrimitive?.contentOrNull).isEqualTo("myenc")
        assertThat(req["type"]?.jsonPrimitive?.contentOrNull).isEqualTo("crypt")
        val params = req["parameters"]?.jsonObject
        assertThat(params?.get("remote")?.jsonPrimitive?.contentOrNull).isEqualTo("gdrive:encrypted")
        assertThat(params?.get("password")?.jsonPrimitive?.contentOrNull).isEqualTo("s3cr3t")
        assertThat(params?.containsKey("password2")).isFalse()
        val opt = req["opt"]?.jsonObject
        assertThat(opt?.get("obscure")?.jsonPrimitive?.booleanOrNull).isTrue()
        assertThat(opt?.get("nonInteractive")?.jsonPrimitive?.booleanOrNull).isTrue()
    }

    @Test fun `createCryptRemote includes password2 when salt is provided`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit

        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "config/create", capture(capturedParams)) } returns buildJsonObject {}

        engine.createCryptRemote("myenc", "s3:bucket/enc", "pass", "saltvalue")

        val params = capturedParams.first()["parameters"]?.jsonObject
        assertThat(params?.get("password2")?.jsonPrimitive?.contentOrNull).isEqualTo("saltvalue")
    }

    @Test fun `createCryptRemote omits password2 when salt is blank`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit

        val capturedParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        coEvery { apiClient.call(any(), any(), any(), "config/create", capture(capturedParams)) } returns buildJsonObject {}

        engine.createCryptRemote("myenc", "dropbox:vault", "mypass", "   ")

        val params = capturedParams.first()["parameters"]?.jsonObject
        assertThat(params?.containsKey("password2")).isFalse()
    }

    @Test fun `createCryptRemote discards config on failure`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "config/create", any()) } throws
            VirgaError.Rclone(exitCode = 400, message = "remote not found")
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.cleanup() } returns Unit

        val error = assertThrows<VirgaError.Rclone> {
            engine.createCryptRemote("enc", "ghost:path", "pass", null)
        }

        assertThat(error.message).contains("remote not found")
        coVerify { configManager.cleanup() }
    }

    // --- importConfig ---

    @Test fun `importConfig validates then commits a config with remotes`() = runTest(testDispatcher) {
        coEvery { configManager.snapshotCiphertext() } returns "old-ciphertext".toByteArray()
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(any()) } returns Unit
        coEvery { configManager.cleanup() } returns Unit
        coEvery { configManager.import(any()) } returns Unit
        // Validation: the freshly imported config parses to one remote.
        coEvery { apiClient.call(any(), any(), any(), "config/dump", any()) } returns buildJsonObject {
            put("gdrive", buildJsonObject { put("type", "drive") })
        }

        engine.importConfig("[gdrive]\ntype=drive\n")

        coVerify(exactly = 1) { configManager.import("[gdrive]\ntype=drive\n") }
        // Success → never rolls back the ciphertext snapshot.
        coVerify(exactly = 0) { configManager.restoreCiphertext(any()) }
    }

    @Test fun `importConfig rolls back to ciphertext snapshot when imported file has no remotes`() = runTest(testDispatcher) {
        val snapshot = "old-ciphertext".toByteArray()
        coEvery { configManager.snapshotCiphertext() } returns snapshot
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(any()) } returns Unit
        coEvery { configManager.cleanup() } returns Unit
        coEvery { configManager.import(any()) } returns Unit
        coEvery { configManager.restoreCiphertext(any()) } returns Unit
        // Validation: the imported text parses to zero remotes → reject + roll back.
        coEvery { apiClient.call(any(), any(), any(), "config/dump", any()) } returns buildJsonObject {}

        assertThrows<VirgaError.Rclone> { engine.importConfig("not a config") }

        coVerify { configManager.import("not a config") }
        coVerify { configManager.restoreCiphertext(snapshot) }
    }

    @Test fun `importConfig rolls back without false-rejecting when the validation daemon fails to start`() =
        runTest(testDispatcher) {
            val snapshot = "old-ciphertext".toByteArray()
            coEvery { configManager.snapshotCiphertext() } returns snapshot
            coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
            // Daemon start fails transiently — NOT a verdict on the imported file.
            coEvery { daemonManager.start(any()) } throws VirgaError.Rclone(message = "daemon did not start")
            coEvery { daemonManager.stop(any()) } returns Unit
            coEvery { configManager.cleanup() } returns Unit
            coEvery { configManager.import(any()) } returns Unit
            coEvery { configManager.restoreCiphertext(any()) } returns Unit

            val error = assertThrows<VirgaError.Rclone> { engine.importConfig("[gdrive]\ntype=drive\n") }

            // Restores the snapshot and reports a retryable engine error, NOT "invalid file".
            coVerify { configManager.restoreCiphertext(snapshot) }
            assertThat(error.message).contains("failed to start")
        }

    // --- config mutations are refused while a sync holds a lease ---

    @Test fun `testConnectivity succeeds when about succeeds`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "operations/about", any()) } returns buildJsonObject {}

        engine.startDaemon()
        val result = engine.testConnectivity("gdrive")

        assertThat(result.isSuccess).isTrue()
    }

    @Test fun `testConnectivity falls back to listDir when about fails`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "operations/about", any()) } throws
            VirgaError.Rclone(message = "not supported")
        coEvery { apiClient.call(any(), any(), any(), "operations/list", any()) } returns buildJsonObject {}

        engine.startDaemon()
        val result = engine.testConnectivity("local")

        assertThat(result.isSuccess).isTrue()
    }

    @Test fun `testConnectivity returns failure when both about and listDir fail`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "operations/about", any()) } throws
            VirgaError.Rclone(message = "not supported")
        coEvery { apiClient.call(any(), any(), any(), "operations/list", any()) } throws
            VirgaError.Network("unreachable")

        engine.startDaemon()
        val result = engine.testConnectivity("broken")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(VirgaError.Network::class.java)
    }

    @Test fun `testConnectivity rethrows cancellation without falling back to listDir`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "operations/about", any()) } throws
            CancellationException("caller cancelled")

        engine.startDaemon()
        assertThrows<CancellationException> { engine.testConnectivity("gdrive") }

        // Cancellation must propagate; the fallback RC call must never fire.
        coVerify(exactly = 0) { apiClient.call(any(), any(), any(), "operations/list", any()) }
    }

    // --- config mutations are refused while a sync holds a lease ---

    @Test fun `createRemote is refused while a sync holds a lease`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        engine.acquireDaemon() // leases = 1 (simulates an in-flight sync)

        assertThrows<VirgaError.Rclone> { engine.createRemote("r", "drive", emptyMap()) }
        // Refused before touching the config store / tearing the daemon down.
        coVerify(exactly = 0) { daemonManager.stop(any()) }
    }

    @Test fun `importConfig is refused while a sync holds a lease`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        engine.acquireDaemon() // leases = 1

        assertThrows<VirgaError.Rclone> { engine.importConfig("[gdrive]\ntype=drive\n") }
        // The refused import must not snapshot, overwrite, or roll back the store.
        coVerify(exactly = 0) { configManager.snapshotCiphertext() }
        coVerify(exactly = 0) { configManager.import(any()) }
    }

    // --- withDaemonForOAuth ---

    @Test fun `withDaemonForOAuth provides daemon and persists config on success`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit

        var receivedDaemon: RcloneDaemon? = null
        engine.withDaemonForOAuth { d -> receivedDaemon = d }

        assertThat(receivedDaemon).isSameInstanceAs(fakeDaemon)
        coVerify { daemonManager.stop(fakeDaemon) }
        coVerify { configManager.persistAndCleanup() }
    }

    @Test fun `withDaemonForOAuth cleans up without persisting on failure`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.cleanup() } returns Unit

        assertThrows<VirgaError.Rclone> {
            engine.withDaemonForOAuth<Unit> { throw VirgaError.Rclone(message = "oauth failed") }
        }

        coVerify(exactly = 0) { configManager.persistAndCleanup() }
        coVerify { configManager.cleanup() }
    }

    @Test fun `withDaemonForOAuth is refused while a sync holds a lease`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit
        engine.acquireDaemon() // leases = 1

        val error = assertThrows<VirgaError.Rclone> {
            engine.withDaemonForOAuth<Unit> { }
        }
        assertThat(error.message).contains("Stop running syncs")

        engine.releaseDaemon()
    }

    @Test fun `withDaemonForOAuth holds a lease not the lock so concurrent ops run during the wait`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit

        // Simulate the minutes-long paste wait: the OAuth block parks here while the
        // engine is held. Under the old exclusive-lock impl the engine Mutex would be
        // held for the whole wait; under the lease impl it stays free.
        val pasteGate = CompletableDeferred<Unit>()
        val oauth = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.withDaemonForOAuth { pasteGate.await() }
        }
        advanceUntilIdle() // OAuth now parked mid-wait, holding a lease (lock released)

        // A concurrent consumer (e.g. a scheduled SyncWorker) acquires the daemon. If the
        // lock were held for the wait this would block until paste/timeout — it must not.
        val concurrent = async(start = CoroutineStart.UNDISPATCHED) { engine.acquireDaemon() }
        advanceUntilIdle()
        assertThat(concurrent.isCompleted).isTrue()
        assertThat(concurrent.await()).isSameInstanceAs(fakeDaemon)
        engine.releaseDaemon() // drop the concurrent consumer's lease (daemon survives)

        // Completing the paste lets OAuth finish; as the last consumer it tears down + persists.
        pasteGate.complete(Unit)
        advanceUntilIdle()
        oauth.join()
        coVerify { daemonManager.stop(fakeDaemon) }
        coVerify { configManager.persistAndCleanup() }
    }

    @Test fun `withDaemonForOAuth failure while a sync co-leases discards the tainted config on the last release`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(fakeDaemon) } returns Unit
        coEvery { configManager.persistAndCleanup() } returns Unit
        coEvery { configManager.cleanup() } returns Unit

        // OAuth takes its lease and parks mid-flow (paste wait), lock released.
        val gate = CompletableDeferred<Unit>()
        val oauth = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { engine.withDaemonForOAuth<Unit> { gate.await(); throw VirgaError.Rclone(message = "oauth failed") } }
        }
        advanceUntilIdle() // parked, leases = 1

        // A concurrent sync co-leases the shared daemon (leases = 2).
        engine.acquireDaemon()

        // OAuth resumes and fails. It can't tear down (sync still leases), so it must
        // NOT persist — it records the discard intent for the last leaseholder instead.
        gate.complete(Unit)
        advanceUntilIdle()
        oauth.join()
        coVerify(exactly = 0) { daemonManager.stop(fakeDaemon) }    // daemon stays up for the sync
        coVerify(exactly = 0) { configManager.persistAndCleanup() } // nothing persisted yet

        // The sync releases last → daemon torn down and the tainted (token-less) config
        // DISCARDED, not persisted — matching withExclusiveDaemon's discard-on-failure.
        engine.releaseDaemon()
        coVerify { daemonManager.stop(fakeDaemon) }
        coVerify { configManager.cleanup() }
        coVerify(exactly = 0) { configManager.persistAndCleanup() }
    }
}
