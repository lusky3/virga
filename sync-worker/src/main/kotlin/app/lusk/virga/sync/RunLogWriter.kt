package app.lusk.virga.sync

import app.lusk.virga.core.common.util.Redaction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accumulates a human-readable, per-run log and writes it to a file under the
 * app's private storage (WS2.5). The log is built from what [SyncWorker] already
 * observes — start header, progress milestones, outcome — so it never contains
 * rclone config or OAuth tokens. A defensive [redact] pass strips anything that
 * still looks token-like before the file is written.
 *
 * (A future enhancement could tee rclone's verbose daemon stderr here for full
 * fidelity; that stream is shared across jobs and would need per-run routing.)
 */
internal class RunLogWriter(filesDir: File, runId: Long) {
    private val dir = File(filesDir, LOG_DIR)
    private val file = File(dir, "$LOG_PREFIX$runId$LOG_SUFFIX")
    private val sb = StringBuilder()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    val path: String get() = file.absolutePath

    fun line(message: String) {
        sb.append(clock.format(Date())).append("  ").append(message).append('\n')
    }

    /**
     * Writes the accumulated log to disk, redacting token-like substrings.
     * Returns true on success so the caller only records [path] on the run when a
     * file actually exists — otherwise the log viewer would point at a missing file.
     */
    fun flush(): Boolean = runCatching {
        dir.mkdirs()
        // Defensive secret redaction (shared with the crash reporter via Redaction).
        file.writeText(Redaction.secrets(sb.toString()))
    }.isSuccess

    companion object {
        private const val LOG_DIR = "run_logs"
        private const val LOG_PREFIX = "run_"
        private const val LOG_SUFFIX = ".log"

        /**
         * Sweeps the per-run log dir, deleting any `run_<id>.log` last modified
         * before [beforeEpochMs] — keeping internal storage bounded on the same
         * cutoff as the DB history prune (the DB-row prune alone left these files
         * to grow without limit). Only our own `run_*.log` files are touched, so a
         * stray or future co-located file is left alone. Tolerates a missing dir
         * and per-file failures.
         */
        fun pruneOlderThan(filesDir: File, beforeEpochMs: Long) {
            val files = File(filesDir, LOG_DIR).listFiles() ?: return
            for (file in files) {
                if (!file.isOwnLog()) continue
                if (file.lastModified() < beforeEpochMs) {
                    runCatching { file.delete() }
                }
            }
        }

        private fun File.isOwnLog(): Boolean =
            isFile && name.startsWith(LOG_PREFIX) && name.endsWith(LOG_SUFFIX)
    }
}
