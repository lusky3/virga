package app.lusk.virga.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pure coverage for [RunLogWriter.pruneOlderThan] — the internal-storage sweep
 * that keeps `run_logs/` bounded on the same cutoff as the DB history prune
 * (0.3.0 log-pruning fix; the DB-row prune alone left these files unbounded).
 *
 * No Android runtime: [RunLogWriter] writes plain files under a `run_logs/`
 * subdir of the supplied dir, so a JUnit5 @TempDir stands in for filesDir and
 * `lastModified()` is set explicitly to straddle the cutoff.
 */
class RunLogWriterTest {

    private fun logFile(filesDir: File, id: Long, lastModified: Long): File {
        // Build a real log via RunLogWriter so the file lands at run_logs/run_<id>.log.
        val writer = RunLogWriter(filesDir, id)
        writer.line("run $id")
        assertThat(writer.flush()).isTrue()
        val file = File(writer.path)
        assertThat(file.setLastModified(lastModified)).isTrue()
        return file
    }

    @Test
    fun `deletes only logs older than the cutoff`(@TempDir filesDir: File) {
        val cutoff = 10_000L
        val old1 = logFile(filesDir, 1L, lastModified = cutoff - 5_000)
        val old2 = logFile(filesDir, 2L, lastModified = cutoff - 1)
        val recent1 = logFile(filesDir, 3L, lastModified = cutoff + 1)
        val recent2 = logFile(filesDir, 4L, lastModified = cutoff + 5_000)

        RunLogWriter.pruneOlderThan(filesDir, cutoff)

        assertThat(old1.exists()).isFalse()
        assertThat(old2.exists()).isFalse()
        assertThat(recent1.exists()).isTrue()
        assertThat(recent2.exists()).isTrue()
    }

    @Test
    fun `file exactly at the cutoff is kept (strict less-than)`(@TempDir filesDir: File) {
        val cutoff = 50_000L
        val boundary = logFile(filesDir, 7L, lastModified = cutoff)

        RunLogWriter.pruneOlderThan(filesDir, cutoff)

        // The prune uses strict `<`, so a file stamped at the cutoff survives.
        assertThat(boundary.exists()).isTrue()
    }

    @Test
    fun `only run_ log files are swept - foreign files are left alone`(@TempDir filesDir: File) {
        val cutoff = 30_000L
        val ourLog = logFile(filesDir, 9L, lastModified = cutoff - 5_000)
        // A co-located, non-run file, also aged well past the cutoff.
        val foreign = File(File(filesDir, "run_logs"), "index.db")
        foreign.writeText("not ours")
        assertThat(foreign.setLastModified(cutoff - 5_000)).isTrue()

        RunLogWriter.pruneOlderThan(filesDir, cutoff)

        assertThat(ourLog.exists()).isFalse()
        assertThat(foreign.exists()).isTrue()
    }

    @Test
    fun `missing run_logs dir is a silent no-op`(@TempDir filesDir: File) {
        // Nothing has created run_logs/ yet — listFiles() is null, so prune returns.
        assertThat(File(filesDir, "run_logs").exists()).isFalse()

        RunLogWriter.pruneOlderThan(filesDir, beforeEpochMs = Long.MAX_VALUE) // does not throw
    }
}
