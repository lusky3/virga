package app.lusk.virga.core.common.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SyncProgressTest {

    private fun progress(transferred: Long, total: Long) = SyncProgress(
        bytesTransferred = transferred,
        totalBytes = total,
        speedBytesPerSec = 0.0,
        transferredFiles = 0,
        totalFiles = 0,
        etaSeconds = null,
        errors = 0,
    )

    @Test fun `fraction is 0 when totalBytes is 0`() {
        assertThat(progress(0L, 0L).fraction).isEqualTo(0f)
    }

    @Test fun `fraction is 0 when nothing transferred yet`() {
        assertThat(progress(0L, 1_000L).fraction).isEqualTo(0f)
    }

    @Test fun `fraction is 1 when fully transferred`() {
        assertThat(progress(500L, 500L).fraction).isEqualTo(1f)
    }

    @Test fun `fraction clamps to 1 when bytes exceed total`() {
        // Should not exceed 1.0 even if rclone reports more bytes than expected total.
        assertThat(progress(600L, 500L).fraction).isEqualTo(1f)
    }

    @Test fun `fraction is never negative`() {
        // totalBytes > 0 but bytesTransferred = 0: fraction must be >= 0
        assertThat(progress(0L, 100L).fraction).isAtLeast(0f)
    }

    @Test fun `fraction midpoint is computed correctly`() {
        val f = progress(250L, 1_000L).fraction
        assertThat(f).isWithin(0.001f).of(0.25f)
    }

    // --- SyncDirection enum ---

    @Test fun `SyncDirection has exactly three values`() {
        assertThat(SyncDirection.values()).hasLength(3)
        assertThat(SyncDirection.values()).asList()
            .containsExactly(SyncDirection.UPLOAD, SyncDirection.DOWNLOAD, SyncDirection.BISYNC)
    }

    // --- SyncStatus enum ---

    @Test fun `SyncStatus has all expected lifecycle states`() {
        val states = SyncStatus.values().map { it.name }
        assertThat(states).containsAtLeast("IDLE", "QUEUED", "RUNNING", "SUCCESS", "FAILED", "CANCELLED")
    }
}
