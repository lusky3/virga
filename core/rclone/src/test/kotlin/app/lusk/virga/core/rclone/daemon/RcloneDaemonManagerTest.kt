package app.lusk.virga.core.rclone.daemon

import android.content.Context
import android.util.Log
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.rclone.RcloneDaemon
import at.favre.lib.crypto.bcrypt.BCrypt
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [RcloneDaemonManager]'s deterministic, process-free logic:
 * the binary-missing guard, the [RcloneDaemonManager.stop] teardown
 * (graceful->forced destroy + htpasswd purge) and [RcloneDaemonManager.isAlive],
 * plus the private htpasswd write/purge lifecycle exercised via reflection.
 *
 * Out of scope (requires a real child process, see report): the serving-banner
 * port-latching, the [RcloneDaemonManager.start] happy path, and the
 * start-failed-after-launch teardown. `start()` constructs `ProcessBuilder(...)
 * .start()` inline with no injection seam, and the port is parsed inside the
 * stderr-drainer thread reading the live process's error stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RcloneDaemonManagerTest {

    private val context = mockk<Context>()
    private val binary = mockk<RcloneBinary>()
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = object : DispatcherProvider {
        override val main get() = testDispatcher
        override val default get() = testDispatcher
        override val io get() = testDispatcher
    }

    @TempDir lateinit var tmpDir: File

    private lateinit var manager: RcloneDaemonManager

    @BeforeEach fun setUp() {
        // android.util.Log is not on the plain-JVM classpath; stub the overloads
        // stop()/writeHtpasswdFile may touch.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        // noBackupFilesDir backs writeHtpasswdFile; the others back start()'s env
        // (unused by the process-free tests but stubbed for safety).
        every { context.noBackupFilesDir } returns tmpDir
        every { context.cacheDir } returns tmpDir
        every { context.filesDir } returns tmpDir
        manager = RcloneDaemonManager(context, binary, dispatchers)
    }

    // --- start(): binary-missing guard ---

    @Test fun `start throws Rclone error and never launches when the binary is missing`() =
        runTest(testDispatcher) {
            val missing = File(tmpDir, "librclone.so")
            every { binary.exists() } returns false
            every { binary.file } returns missing

            val error = assertThrows<VirgaError.Rclone> { manager.start(File(tmpDir, "rclone.conf")) }

            assertThat(error.message).contains("rclone binary missing")
            assertThat(error.message).contains(missing.path)
            // The guard fires before any htpasswd file is written.
            assertThat(tmpDir.listFiles { f -> f.name.startsWith("rc-auth-") } ?: emptyArray())
                .isEmpty()
        }

    // --- stop(): graceful vs forced teardown + htpasswd purge ---

    @Test fun `stop destroys gracefully and deletes the htpasswd file when it exits in time`() =
        runTest(testDispatcher) {
            val process = mockk<Process>(relaxed = true)
            // Process exits within the grace window: no forced kill.
            every { process.waitFor(any(), any()) } returns true
            val htpasswd = File(tmpDir, "rc-auth-1.htpasswd").apply { writeText("u:hash\n") }
            val daemon = RcloneDaemon(process, port = 5572, user = "u", pass = "p", htpasswdFile = htpasswd)

            manager.stop(daemon)

            verify(exactly = 1) { process.destroy() }
            verify(exactly = 0) { process.destroyForcibly() }
            // The credential file is removed now that the process is gone.
            assertThat(htpasswd.exists()).isFalse()
        }

    @Test fun `stop force-kills when the process does not exit within the grace window`() =
        runTest(testDispatcher) {
            val process = mockk<Process>(relaxed = true)
            // Grace window elapses without exit -> escalate to destroyForcibly.
            every { process.waitFor(any(), any()) } returns false
            val htpasswd = File(tmpDir, "rc-auth-2.htpasswd").apply { writeText("u:hash\n") }
            val daemon = RcloneDaemon(process, port = 5572, user = "u", pass = "p", htpasswdFile = htpasswd)

            manager.stop(daemon)

            verify(exactly = 1) { process.destroy() }
            verify(exactly = 1) { process.destroyForcibly() }
            verify { process.waitFor(any(), TimeUnit.MILLISECONDS) }
            assertThat(htpasswd.exists()).isFalse()
        }

    @Test fun `stop warns but does not throw when the htpasswd file cannot be deleted`() =
        runTest(testDispatcher) {
            val process = mockk<Process>(relaxed = true)
            every { process.waitFor(any(), any()) } returns true
            // delete() returns false: simulate an undeletable file via a mock File.
            val htpasswd = mockk<File>(relaxed = true)
            every { htpasswd.delete() } returns false
            every { htpasswd.name } returns "rc-auth-stuck.htpasswd"
            val daemon = RcloneDaemon(process, port = 5572, user = "u", pass = "p", htpasswdFile = htpasswd)

            manager.stop(daemon) // must complete, not throw

            verify { Log.w("RcloneDaemon", match<String> { it.contains("Failed to delete htpasswd") }) }
        }

    @Test fun `stop tolerates a null htpasswd file (test-created daemon)`() =
        runTest(testDispatcher) {
            val process = mockk<Process>(relaxed = true)
            every { process.waitFor(any(), any()) } returns true
            val daemon = RcloneDaemon(process, port = 5572, user = "u", pass = "p", htpasswdFile = null)

            manager.stop(daemon) // null?.delete() == null, branch is skipped

            verify(exactly = 1) { process.destroy() }
            verify(exactly = 0) { Log.w(any<String>(), any<String>()) }
        }

    // --- isAlive() ---

    @Test fun `isAlive delegates to the process liveness`() {
        val live = mockk<Process> { every { isAlive } returns true }
        val dead = mockk<Process> { every { isAlive } returns false }

        assertThat(manager.isAlive(RcloneDaemon(live, 1, "u", "p"))).isTrue()
        assertThat(manager.isAlive(RcloneDaemon(dead, 1, "u", "p"))).isFalse()
    }

    // --- writeHtpasswdFile() (private; reached via reflection) ---
    //
    // The method is the SEC-H1 credential-handoff seam: it must produce a private
    // `user:bcrypt(pass)` file in noBackupFilesDir and purge any stale ones first.
    // It is pure I/O (no process), so a real temp dir exercises it deterministically.

    private fun writeHtpasswd(user: String, pass: String): File {
        val m = RcloneDaemonManager::class.java
            .getDeclaredMethod("writeHtpasswdFile", String::class.java, String::class.java)
            .apply { isAccessible = true }
        return m.invoke(manager, user, pass) as File
    }

    @Test fun `writeHtpasswdFile writes a user colon bcrypt-hash line that verifies`() {
        val file = writeHtpasswd("alice", "s3cr3t-token")

        assertThat(file.parentFile).isEqualTo(tmpDir)
        assertThat(file.name).startsWith("rc-auth-")
        assertThat(file.name).endsWith(".htpasswd")

        val line = file.readText().trimEnd('\n')
        val colon = line.indexOf(':')
        assertThat(colon).isGreaterThan(0)
        val storedUser = line.substring(0, colon)
        val hash = line.substring(colon + 1)
        assertThat(storedUser).isEqualTo("alice")
        // bcrypt $2a$ entry that rclone's htpasswd parser accepts, and which
        // actually verifies against the original password.
        assertThat(hash).startsWith("\$2a\$")
        assertThat(BCrypt.verifyer().verify("s3cr3t-token".toCharArray(), hash).verified).isTrue()
    }

    @Test fun `writeHtpasswdFile purges stale rc-auth files before writing the new one`() {
        // Orphans from an abnormally-terminated prior daemon plus an unrelated file.
        val stale1 = File(tmpDir, "rc-auth-old1.htpasswd").apply { writeText("x:y\n") }
        val stale2 = File(tmpDir, "rc-auth-old2.htpasswd").apply { writeText("x:y\n") }
        val unrelated = File(tmpDir, "keep-me.txt").apply { writeText("data") }

        val fresh = writeHtpasswd("bob", "pw")

        assertThat(stale1.exists()).isFalse()
        assertThat(stale2.exists()).isFalse()
        // Non-matching files are left untouched.
        assertThat(unrelated.exists()).isTrue()
        // Exactly one rc-auth file remains: the freshly written one.
        val remaining = tmpDir.listFiles { f -> f.name.startsWith("rc-auth-") }!!.toList()
        assertThat(remaining).containsExactly(fresh)
    }
}
