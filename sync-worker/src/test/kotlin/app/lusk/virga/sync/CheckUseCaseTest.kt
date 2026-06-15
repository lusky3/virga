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

class CheckUseCaseTest {

    private val task = SyncTask(
        name = "Photos",
        sourcePath = "/sdcard/DCIM",
        remoteName = "drive",
        remotePath = "Backup",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
    )

    @Test
    fun verify_reportsDifferencesFromTerminalErrors() = runBlocking {
        val engine = mockk<RcloneEngine>(relaxed = true)
        val executor = mockk<SyncExecutor>()
        every { executor.runCheck(any()) } returns flowOf(
            SyncProgress(
                bytesTransferred = 0L,
                totalBytes = 0L,
                speedBytesPerSec = 0.0,
                transferredFiles = 0,
                totalFiles = 10,
                etaSeconds = null,
                errors = 3,
            ),
        )

        val result = CheckUseCase(executor, engine).verify(task)

        assertThat(result.differences).isEqualTo(3)
        assertThat(result.error).isNull()
        coVerify { engine.releaseDaemon() }
    }

    @Test
    fun verify_lastEmissionWins_whenMultipleProgressItems() = runBlocking {
        val engine = mockk<RcloneEngine>(relaxed = true)
        val executor = mockk<SyncExecutor>()
        every { executor.runCheck(any()) } returns flowOf(
            SyncProgress(0L, 0L, 0.0, 0, 5, null, 0),
            SyncProgress(0L, 0L, 0.0, 0, 10, null, 2),
        )

        val result = CheckUseCase(executor, engine).verify(task)

        assertThat(result.differences).isEqualTo(2)
    }

    @Test
    fun verify_capturesError() = runBlocking {
        val engine = mockk<RcloneEngine>(relaxed = true)
        val executor = mockk<SyncExecutor>()
        every { executor.runCheck(any()) } returns flow {
            throw IllegalStateException("remote unreachable")
        }

        val result = CheckUseCase(executor, engine).verify(task)

        assertThat(result.error).isEqualTo("remote unreachable")
        coVerify { engine.releaseDaemon() }
    }

    @Test
    fun verify_emptyFlow_returnsZeroResult() = runBlocking {
        val engine = mockk<RcloneEngine>(relaxed = true)
        val executor = mockk<SyncExecutor>()
        every { executor.runCheck(any()) } returns flowOf()

        val result = CheckUseCase(executor, engine).verify(task)

        assertThat(result.differences).isEqualTo(0)
        assertThat(result.error).isNull()
    }

    @Test
    fun isAvailableFor_isFalseForSafSource() {
        val useCase = CheckUseCase(mockk(), mockk())
        assertThat(useCase.isAvailableFor(task)).isTrue()
        assertThat(useCase.isAvailableFor(task.copy(sourcePath = "content://tree/primary"))).isFalse()
    }

    @Test
    fun isAvailableFor_isTrueForFilesystemSource() {
        val useCase = CheckUseCase(mockk(), mockk())
        assertThat(useCase.isAvailableFor(task.copy(sourcePath = "/storage/emulated/0/DCIM"))).isTrue()
    }
}
