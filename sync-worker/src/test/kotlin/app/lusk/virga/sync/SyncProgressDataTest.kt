package app.lusk.virga.sync

import androidx.work.Data
import app.lusk.virga.core.common.model.SyncProgress
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [SyncProgressData] encode/decode round-trips and [SyncProgressData.phaseOf].
 * [Data]/[workDataOf] requires Android context to build internally, so Robolectric
 * is used — matching the pattern in [SyncSchedulerTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncProgressDataTest {

    // ---------------------------------------------------------------------------
    // encode → decode round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `encode then decode round-trips all fields including deletes`() {
        val progress = SyncProgress(
            bytesTransferred = 1_024L,
            totalBytes = 8_192L,
            speedBytesPerSec = 512.5,
            transferredFiles = 3,
            totalFiles = 12,
            etaSeconds = 42L,
            errors = 2,
            deletes = 7,
        )

        val decoded = SyncProgressData.decode(SyncProgressData.encode(progress))!!

        assertThat(decoded.bytesTransferred).isEqualTo(1_024L)
        assertThat(decoded.totalBytes).isEqualTo(8_192L)
        assertThat(decoded.speedBytesPerSec).isWithin(0.001).of(512.5)
        assertThat(decoded.transferredFiles).isEqualTo(3)
        assertThat(decoded.totalFiles).isEqualTo(12)
        assertThat(decoded.etaSeconds).isEqualTo(42L)
        assertThat(decoded.errors).isEqualTo(2)
        assertThat(decoded.deletes).isEqualTo(7)
    }

    @Test
    fun `encode then decode round-trips deletes=0`() {
        val progress = SyncProgress(
            bytesTransferred = 0L,
            totalBytes = 100L,
            speedBytesPerSec = 0.0,
            transferredFiles = 0,
            totalFiles = 1,
            etaSeconds = null,
            errors = 0,
            deletes = 0,
        )

        val decoded = SyncProgressData.decode(SyncProgressData.encode(progress))!!

        assertThat(decoded.deletes).isEqualTo(0)
    }

    // ---------------------------------------------------------------------------
    // etaSeconds sentinel handling
    // ---------------------------------------------------------------------------

    @Test
    fun `etaSeconds null round-trips via -1 sentinel back to null`() {
        val progress = SyncProgress(
            bytesTransferred = 0L,
            totalBytes = 1_000L,
            speedBytesPerSec = 0.0,
            transferredFiles = 0,
            totalFiles = 5,
            etaSeconds = null,
            errors = 0,
        )

        val decoded = SyncProgressData.decode(SyncProgressData.encode(progress))!!

        assertThat(decoded.etaSeconds).isNull()
    }

    @Test
    fun `etaSeconds non-null value round-trips correctly`() {
        val progress = SyncProgress(
            bytesTransferred = 500L,
            totalBytes = 1_000L,
            speedBytesPerSec = 10.0,
            transferredFiles = 2,
            totalFiles = 4,
            etaSeconds = 99L,
            errors = 0,
        )

        val decoded = SyncProgressData.decode(SyncProgressData.encode(progress))!!

        assertThat(decoded.etaSeconds).isEqualTo(99L)
    }

    // ---------------------------------------------------------------------------
    // decode of empty / missing-key Data
    // ---------------------------------------------------------------------------

    @Test
    fun `decode of Data with no progress keys returns null`() {
        val empty = Data.EMPTY

        assertThat(SyncProgressData.decode(empty)).isNull()
    }

    // ---------------------------------------------------------------------------
    // phaseOf
    // ---------------------------------------------------------------------------

    @Test
    fun `phaseOf returns LISTING when totalBytes and totalFiles are both zero`() {
        val progress = SyncProgress(
            bytesTransferred = 0L,
            totalBytes = 0L,
            speedBytesPerSec = 0.0,
            transferredFiles = 0,
            totalFiles = 0,
            etaSeconds = null,
            errors = 0,
        )

        assertThat(SyncProgressData.phaseOf(progress)).isEqualTo(SyncPhase.LISTING)
    }

    @Test
    fun `phaseOf returns TRANSFERRING when totalBytes is greater than zero`() {
        val progress = SyncProgress(
            bytesTransferred = 0L,
            totalBytes = 4_096L,
            speedBytesPerSec = 0.0,
            transferredFiles = 0,
            totalFiles = 0,
            etaSeconds = null,
            errors = 0,
        )

        assertThat(SyncProgressData.phaseOf(progress)).isEqualTo(SyncPhase.TRANSFERRING)
    }

    @Test
    fun `phaseOf returns TRANSFERRING when totalFiles is greater than zero`() {
        val progress = SyncProgress(
            bytesTransferred = 0L,
            totalBytes = 0L,
            speedBytesPerSec = 0.0,
            transferredFiles = 0,
            totalFiles = 3,
            etaSeconds = null,
            errors = 0,
        )

        assertThat(SyncProgressData.phaseOf(progress)).isEqualTo(SyncPhase.TRANSFERRING)
    }

    @Test
    fun `phaseOf returns TRANSFERRING when both totalBytes and totalFiles are greater than zero`() {
        val progress = SyncProgress(
            bytesTransferred = 100L,
            totalBytes = 1_000L,
            speedBytesPerSec = 50.0,
            transferredFiles = 1,
            totalFiles = 10,
            etaSeconds = 18L,
            errors = 0,
        )

        assertThat(SyncProgressData.phaseOf(progress)).isEqualTo(SyncPhase.TRANSFERRING)
    }
}
