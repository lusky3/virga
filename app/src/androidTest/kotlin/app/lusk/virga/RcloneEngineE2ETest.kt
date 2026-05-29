package app.lusk.virga

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.lusk.virga.core.common.dispatchers.DefaultDispatcherProvider
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.rclone.RcloneEngineImpl
import app.lusk.virga.core.rclone.SyncOptions
import app.lusk.virga.core.rclone.api.RcApiClient
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import app.lusk.virga.core.rclone.daemon.RcloneBinary
import app.lusk.virga.core.rclone.daemon.RcloneDaemonManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * End-to-end instrumented test that exercises the bundled rclone binary against
 * the real device filesystem. Runs a local→local sync — no remote backend, no
 * network, no OAuth — proving the daemon starts, the RC API responds, and the
 * sync actually moves bytes.
 *
 * This is the canary that catches:
 *   - ABI mismatches (binary won't exec on this arch),
 *   - Missing native lib extraction (legacy packaging regression),
 *   - Daemon startup banner format changes (port-parsing regex),
 *   - RC API contract drift (sync/sync endpoint changes).
 */
class RcloneEngineE2ETest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultDispatcherProvider()

    private lateinit var engine: RcloneEngineImpl
    private lateinit var workDir: File

    @Before
    fun setUp() {
        val binary = RcloneBinary(context)
        check(binary.exists()) { "rclone binary missing at ${binary.file}" }

        val daemonManager = RcloneDaemonManager(context, binary, dispatchers)
        val configManager = RcloneConfigManager(context, dispatchers)
        val apiClient = RcApiClient(OkHttpClient())
        engine = RcloneEngineImpl(daemonManager, configManager, apiClient, dispatchers)

        workDir = File(context.cacheDir, "rclone-e2e-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        runBlocking { engine.stopDaemon() }
        workDir.deleteRecursively()
    }

    @Test
    fun localToLocalSync_copiesFilesAndRecordsFinalProgress() = runBlocking {
        val source = File(workDir, "src").apply { mkdirs() }
        val dest = File(workDir, "dst").apply { mkdirs() }
        File(source, "alpha.txt").writeText("alpha contents")
        File(source, "beta.txt").writeText("beta contents")
        File(source, "sub").mkdirs()
        File(source, "sub/gamma.txt").writeText("gamma contents")

        // Daemon must be running before we issue sync/sync.
        val daemon = engine.startDaemon()
        assertThat(daemon.port).isGreaterThan(1024)
        // Allow ~1s for the RC server to fully bind after the "Serving" log
        // banner. Without this, immediate `rc/noop` sometimes 503s on slow
        // emulators.
        kotlinx.coroutines.delay(500)

        // rclone treats unprefixed absolute paths as local filesystem paths,
        // so this exercises the real sync engine without any remote config.
        val progress = engine.sync(
            source = source.absolutePath,
            dest = dest.absolutePath,
            options = SyncOptions(
                direction = SyncDirection.UPLOAD,
                transfers = 2,
                checkers = 2,
                bufferSize = "1M",
            ),
        ).toList()

        assertThat(progress).isNotEmpty()
        val finalProgress = progress.last()
        assertThat(finalProgress.transferredFiles).isAtLeast(3)
        assertThat(finalProgress.errors).isEqualTo(0)

        // Verify the destination actually has the files with correct contents —
        // the canonical "did the sync work" assertion.
        assertThat(File(dest, "alpha.txt").readText()).isEqualTo("alpha contents")
        assertThat(File(dest, "beta.txt").readText()).isEqualTo("beta contents")
        assertThat(File(dest, "sub/gamma.txt").readText()).isEqualTo("gamma contents")
    }

    @Test
    fun importConfig_persistsRemotesAcrossDaemonRestart() = runBlocking {
        // Drive the config layer end-to-end: import a local-backend remote,
        // verify rclone enumerates it, restart the daemon, and confirm the
        // encrypted config survived.
        val confText = """
            [scratch]
            type = local
        """.trimIndent()

        engine.importConfig(confText).getOrThrow()
        var remotes = engine.listRemotes()
        assertThat(remotes.map { it.name }).contains("scratch")

        engine.stopDaemon()
        // Reading remotes after stop forces a fresh daemon start against the
        // encrypted-at-rest config.
        remotes = engine.listRemotes()
        assertThat(remotes.map { it.name }).contains("scratch")
    }
}
