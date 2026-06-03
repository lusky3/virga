package app.lusk.virga.core.rclone.daemon

import android.content.Context
import android.util.Log
import app.lusk.virga.core.rclone.BuildConfig
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.rclone.RcloneDaemon
import dagger.hilt.android.qualifiers.ApplicationContext
import at.favre.lib.crypto.bcrypt.BCrypt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the rclone RC daemon child process: starts it with random localhost port
 * and per-session Basic-auth credentials, discovers the bound port from rclone's
 * startup log, and tears it down on stop.
 */
@Singleton
class RcloneDaemonManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val binary: RcloneBinary,
    private val dispatchers: DispatcherProvider,
) {
    /**
     * Starts a daemon using [configFile] as the rclone config. Reads stderr until
     * the "Serving remote control" banner reveals the bound port.
     */
    suspend fun start(configFile: File): RcloneDaemon = withContext(dispatchers.io) {
        if (!binary.exists()) {
            throw VirgaError.Rclone(message = "rclone binary missing at ${binary.file.path}")
        }
        val user = randomToken()
        val pass = randomToken()

        // SEC-H1: Pass RC credentials via a private htpasswd file rather than
        // --rc-user/--rc-pass command-line args, which are world-readable via
        // /proc/<pid>/cmdline. The file uses a bcrypt ($2a$) hash supported by
        // rclone's htpasswd parser, and lives in noBackupFilesDir (private,
        // backup-excluded). rclone
        // re-reads (stat + open) this file on EVERY RC request via go-http-auth's
        // ReloadIfNeeded, so it MUST persist for the daemon's whole lifetime —
        // it is deleted in stop() and on the startup-failure paths below, never
        // immediately after launch (doing so panics every authenticated request).
        val htpasswdFile = writeHtpasswdFile(user, pass)
        val process = try {
            ProcessBuilder(
                binary.file.absolutePath,
                "rcd",
                "--rc-addr=127.0.0.1:0",
                "--rc-htpasswd=${htpasswdFile.absolutePath}",
                "--config=${configFile.absolutePath}",
                "--cache-dir=${context.cacheDir.absolutePath}",
                "--use-json-log",
                "--log-level=INFO",
            ).apply {
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                environment()["HOME"] = context.filesDir.absolutePath
                redirectErrorStream(false)
            }.start()
        } catch (t: Throwable) {
            htpasswdFile.delete()
            throw t
        }

        // Read stderr line-by-line both to discover the bound port and to keep
        // the pipe drained for the daemon's lifetime. rclone writes INFO logs
        // continuously; if nobody reads them, the OS pipe buffer (~64 KiB)
        // fills and the daemon blocks on its next write. The drainer runs on a
        // daemon thread so it does not keep the process alive after shutdown.
        val stderr = BufferedReader(InputStreamReader(process.errorStream))
        val boundPort = java.util.concurrent.atomic.AtomicReference<Int?>(null)
        Thread({
            try {
                stderr.forEachLine { line ->
                    // Only extract the port from the actual serving-banner line, not
                    // any incidental 127.0.0.1:<n> that rclone might log earlier —
                    // latching the wrong port would point the RC client at nothing.
                    if (boundPort.get() == null && line.contains(SERVING_BANNER)) {
                        SERVING_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                            boundPort.set(it)
                        }
                    }
                    // SEC-M1: Gate daemon stderr logging behind DEBUG flag so
                    // rclone's verbose INFO stream never lands in a release logcat.
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, line)
                    }
                }
            } catch (_: Throwable) {
                // Stream closed on shutdown; nothing to do.
            }
        }, "rclone-stderr-drainer").apply {
            isDaemon = true
            start()
        }

        try {
            // Cancellable wait via delay() (not Thread.sleep): if the caller's
            // coroutine is cancelled during a cold start, the wait unwinds promptly
            // and the catch below tears down the process we already launched, instead
            // of blocking an IO thread for up to STARTUP_TIMEOUT_MS uninterruptibly.
            val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
            while (boundPort.get() == null && System.currentTimeMillis() < deadline && process.isAlive) {
                delay(50)
            }
            val port = boundPort.get()
                ?: throw VirgaError.Rclone(
                    message = "rclone daemon did not start within ${STARTUP_TIMEOUT_MS}ms",
                )
            RcloneDaemon(process = process, port = port, user = user, pass = pass, htpasswdFile = htpasswdFile)
        } catch (t: Throwable) {
            // Timeout, cancellation, or any other failure after launch: don't leak
            // the child process or its credential file.
            process.destroyForcibly()
            htpasswdFile.delete()
            throw t
        }
    }

    suspend fun stop(daemon: RcloneDaemon) = withContext(dispatchers.io) {
        daemon.process.destroy()
        // waitFor(timeout) blocks until the process has fully exited (and flushed its
        // file descriptors), unlike a spin on isAlive which returns at process-exit
        // observability — so a token rclone refreshed during shutdown is durable on
        // disk before persistAndCleanup re-encrypts the config.
        if (!daemon.process.waitFor(GRACEFUL_STOP_MS, TimeUnit.MILLISECONDS)) {
            daemon.process.destroyForcibly()
        }
        // SEC-H1: the htpasswd file lived for the daemon's lifetime; remove it now that
        // the process is gone so no credential hash lingers. A failed delete is purged
        // by the next daemon start (writeHtpasswdFile), but log it for observability.
        if (daemon.htpasswdFile?.delete() == false) {
            Log.w(TAG, "Failed to delete htpasswd file ${daemon.htpasswdFile.name}; next start will purge it")
        }
        Unit
    }

    fun isAlive(daemon: RcloneDaemon): Boolean = daemon.process.isAlive

    /**
     * Writes a temporary htpasswd file containing [user]:<bcrypt(pass)>.
     * rclone's htpasswd parser accepts bcrypt ($2a$) entries. bcrypt replaces the
     * former unsalted Apache SHA-1 format. The password is a 144-bit SecureRandom
     * token, so cost 10 is more than sufficient and adds only a one-time startup cost.
     * File is placed in noBackupFilesDir which is private and excluded from backups.
     */
    private fun writeHtpasswdFile(user: String, pass: String): File {
        val hash = BCrypt.withDefaults().hashToString(10, pass.toCharArray())
        val line = "$user:$hash\n"
        val dir = context.noBackupFilesDir.also { it.mkdirs() }
        // Purge htpasswd files orphaned by an abnormally-terminated prior daemon.
        // Only one daemon is ever live at a time, so any pre-existing ones are
        // stale credential hashes that should not linger on disk.
        dir.listFiles { f -> f.name.startsWith("rc-auth-") && f.name.endsWith(".htpasswd") }
            ?.forEach { it.delete() }
        val file = File(dir, "rc-auth-${System.nanoTime()}.htpasswd")
        file.writeText(line, Charsets.UTF_8)
        return file
    }

    private fun randomToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val STARTUP_TIMEOUT_MS = 15_000L
        const val GRACEFUL_STOP_MS = 3_000L
        const val TAG = "RcloneDaemon"
        // rclone logs this banner (plain and json-log forms) when the RC server is
        // up; the loopback port is extracted only from that line.
        const val SERVING_BANNER = "Serving remote control on"
        val SERVING_REGEX = Regex("""127\.0\.0\.1:(\d+)""")
    }
}
