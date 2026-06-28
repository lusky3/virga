package app.lusk.virga.sync

import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncTask
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SyncWorkerStallMessageTest {

    @Test
    fun `upload stall on a local source blames the card and names the file`() {
        val msg = stallUserMessage(
            VirgaError.Stall(file = "DCIM/IMG_BAD.jpg", message = "stalled"),
            direction = SyncDirection.UPLOAD,
            sourceIsLocal = true,
        )
        assertThat(msg).contains("DCIM/IMG_BAD.jpg")
        assertThat(msg).contains("card")
    }

    @Test
    fun `download stall points at the connection, not the card`() {
        val msg = stallUserMessage(
            VirgaError.Stall(file = null, message = "stalled"),
            direction = SyncDirection.DOWNLOAD,
            sourceIsLocal = false,
        )
        assertThat(msg).contains("connection")
        assertThat(msg).doesNotContain("card")
    }

    @Test
    fun `a stall is never retried even when retryOnRclone is on`() {
        val task = sampleTask(retryOnRclone = true, maxRetries = 3)
        val decision = retryDecisionFor(
            VirgaError.Stall(message = "stalled"), attempt = 0, task = task,
        )
        assertThat(decision).isEqualTo(RetryOutcome.FAIL)
    }

    @Test
    fun `a network error still retries within the attempt budget`() {
        val task = sampleTask(retryOnRclone = false, maxRetries = 3)
        val decision = retryDecisionFor(
            VirgaError.Network("offline"), attempt = 0, task = task,
        )
        assertThat(decision).isEqualTo(RetryOutcome.RETRY)
    }

    /** Minimal [SyncTask] for the pure retry-policy tests: fills the required fields with
     *  trivial valid values and leaves the rest defaulted. Not a production/DB builder. */
    private fun sampleTask(retryOnRclone: Boolean, maxRetries: Int): SyncTask = SyncTask(
        name = "t",
        sourcePath = "/src",
        remoteName = "r",
        remotePath = "/dst",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
        retryOnRclone = retryOnRclone,
        maxRetries = maxRetries,
    )
}
