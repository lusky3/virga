package app.lusk.virga.core.rclone.daemon

import android.content.Context
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.rclone.RcloneDaemon
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.security.SecureRandom
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

        val process = ProcessBuilder(
            binary.file.absolutePath,
            "rcd",
            "--rc-addr=127.0.0.1:0",
            "--rc-user=$user",
            "--rc-pass=$pass",
            "--config=${configFile.absolutePath}",
            "--cache-dir=${context.cacheDir.absolutePath}",
            "--use-json-log",
            "--log-level=INFO",
        ).apply {
            environment()["TMPDIR"] = context.cacheDir.absolutePath
            environment()["HOME"] = context.filesDir.absolutePath
            redirectErrorStream(false)
        }.start()

        val port = withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
            readBoundPort(process.errorStream.bufferedReader())
        }
        if (port == null) {
            process.destroyForcibly()
            throw VirgaError.Rclone(message = "rclone daemon did not start within ${STARTUP_TIMEOUT_MS}ms")
        }
        RcloneDaemon(process = process, port = port, user = user, pass = pass)
    }

    suspend fun stop(daemon: RcloneDaemon) = withContext(dispatchers.io) {
        daemon.process.destroy()
        if (!daemon.process.waitForCompat(GRACEFUL_STOP_MS)) {
            daemon.process.destroyForcibly()
        }
    }

    fun isAlive(daemon: RcloneDaemon): Boolean = daemon.process.isAlive

    /** Scans daemon log lines for the bound port. rclone prints e.g.
     *  `Serving remote control on http://127.0.0.1:39145/`. */
    private fun readBoundPort(reader: BufferedReader): Int? {
        reader.useLines { lines ->
            for (line in lines) {
                val match = SERVING_REGEX.find(line)
                if (match != null) return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    private fun randomToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun Process.waitForCompat(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive) return true
            Thread.sleep(25)
        }
        return !isAlive
    }

    private companion object {
        const val STARTUP_TIMEOUT_MS = 15_000L
        const val GRACEFUL_STOP_MS = 3_000L
        // Matches both plain and json-log forms containing the URL with the port.
        val SERVING_REGEX = Regex("""127\.0\.0\.1:(\d+)""")
    }
}
