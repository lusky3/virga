package app.lusk.virga.sync

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.rclone.RcloneEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DryRunUseCaseTest {

    private val task = SyncTask(
        name = "Photos",
        sourcePath = "/sdcard/DCIM",
        remoteName = "drive",
        remotePath = "Backup",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
    )

    @Test
    fun preview_reportsPlannedTotals_notTransferredStats() = runBlocking {
        val engine = mockk<RcloneEngine>(relaxed = true)
        val executor = mockk<SyncExecutor>()
        // Dry-run: rclone transfers nothing (transferred* = 0) but reports the
        // planned change-set in total* — the preview must read the totals.
        every { executor.run(any(), any(), any(), any(), any()) } returns flowOf(
            SyncProgress(
                bytesTransferred = 0L,
                totalBytes = 2048L,
                speedBytesPerSec = 0.0,
                transferredFiles = 0,
                totalFiles = 7,
                etaSeconds = null,
                errors = 0,
            ),
        )

        val result = DryRunUseCase(executor, engine).preview(task)

        assertThat(result.filesToTransfer).isEqualTo(7)
        assertThat(result.bytesToTransfer).isEqualTo(2048L)
        assertThat(result.error).isNull()
        coVerify { engine.releaseDaemon() }
    }

    @Test
    fun preview_capturesError() = runBlocking {
        val engine = mockk<RcloneEngine>(relaxed = true)
        val executor = mockk<SyncExecutor>()
        every { executor.run(any(), any(), any(), any(), any()) } returns flow {
            throw IllegalStateException("remote unreachable")
        }

        val result = DryRunUseCase(executor, engine).preview(task)

        assertThat(result.error).isEqualTo("remote unreachable")
    }

    @Test
    fun isAvailableFor_isFalseForSafSource() {
        val useCase = DryRunUseCase(mockk(), mockk())
        assertThat(useCase.isAvailableFor(task)).isTrue()
        assertThat(useCase.isAvailableFor(task.copy(sourcePath = "content://tree/primary"))).isFalse()
    }
}
