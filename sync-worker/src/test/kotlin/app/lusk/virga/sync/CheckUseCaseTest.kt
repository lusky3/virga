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

    // --- acquireDaemon failure before the flow starts ----------------------
    // This exercises the outer catch(e: Throwable) block that wraps acquireDaemon.
    // The flow .catch only fires for errors inside the flow; a daemon-launch
    // failure throws synchronously before the flow is collected.

    @Test
    fun verify_acquireDaemonThrows_surfacesErrorWithoutThrowing() = runBlocking {
        val engine = mockk<RcloneEngine>(relaxed = true)
        val executor = mockk<SyncExecutor>()
        // Simulate acquireDaemon failing (e.g. daemon cannot start).
        io.mockk.coEvery { engine.acquireDaemon() } throws IllegalStateException("daemon launch failed")
        // executor.runCheck should never be reached; no stub needed.

        val result = CheckUseCase(executor, engine).verify(task)

        // Should NOT rethrow; maps to an error result.
        assertThat(result.error).isEqualTo("daemon launch failed")
        // leased=false so releaseDaemon must NOT be called.
        coVerify(exactly = 0) { engine.releaseDaemon() }
    }

    @Test
    fun verify_acquireDaemonThrowsWithNullMessage_usesGenericMessage() = runBlocking {
        val engine = mockk<RcloneEngine>(relaxed = true)
        val executor = mockk<SyncExecutor>()
        // A Throwable whose message is null (e.g. NullPointerException()).
        io.mockk.coEvery { engine.acquireDaemon() } throws NullPointerException()

        val result = CheckUseCase(executor, engine).verify(task)

        assertThat(result.error).isEqualTo("Verify failed")
    }

    @Test
    fun verify_flowCatchWithNullMessage_usesGenericMessage() = runBlocking {
        val engine = mockk<RcloneEngine>(relaxed = true)
        val executor = mockk<SyncExecutor>()
        every { executor.runCheck(any()) } returns flow {
            // A Throwable whose message is null propagates to .catch.
            throw NullPointerException()
        }

        val result = CheckUseCase(executor, engine).verify(task)

        assertThat(result.error).isEqualTo("Verify failed")
        coVerify { engine.releaseDaemon() }
    }
}
