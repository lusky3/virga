package app.lusk.virga.core.data

import app.lusk.virga.core.rclone.RcloneEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FileBrowserRepository] verifying that each method passes the correct
 * fs/remote parameters to [RcloneEngine] and that path composition is consistent.
 */
class FileBrowserRepositoryTest {

    private val engine: RcloneEngine = mockk(relaxed = true)
    private val repo = FileBrowserRepository(engine)

    // -------------------------------------------------------------------------
    // list
    // -------------------------------------------------------------------------

    @Test fun `list composes remoteName colon and delegates to listDir`() = runTest {
        coEvery { engine.listDir(any(), any()) } returns emptyList()

        repo.list("gdrive", "Photos")

        coVerify { engine.listDir("gdrive:", "Photos") }
    }

    // -------------------------------------------------------------------------
    // mkdir
    // -------------------------------------------------------------------------

    @Test fun `mkdir composes remoteName colon and delegates to engine mkdir`() = runTest {
        repo.mkdir("gdrive", "Photos/2024")

        coVerify { engine.mkdir("gdrive:", "Photos/2024") }
    }

    // -------------------------------------------------------------------------
    // deleteFile
    // -------------------------------------------------------------------------

    @Test fun `deleteFile composes remoteName colon fs and delegates path`() = runTest {
        repo.deleteFile("gdrive", "Photos/img.jpg")

        coVerify { engine.deleteFile("gdrive:", "Photos/img.jpg") }
    }

    @Test fun `deleteFile passes empty path for root-level files`() = runTest {
        repo.deleteFile("mys3", "file.txt")

        coVerify { engine.deleteFile("mys3:", "file.txt") }
    }

    // -------------------------------------------------------------------------
    // moveFile
    // -------------------------------------------------------------------------

    @Test fun `moveFile composes remoteName colon fromPath and toPath`() = runTest {
        repo.moveFile("gdrive", "Photos/old.jpg", "Photos/new.jpg")

        coVerify { engine.moveFile("gdrive:Photos/old.jpg", "gdrive:Photos/new.jpg") }
    }

    @Test fun `moveFile can rename a root-level file`() = runTest {
        repo.moveFile("gdrive", "old.txt", "renamed.txt")

        coVerify { engine.moveFile("gdrive:old.txt", "gdrive:renamed.txt") }
    }

    // -------------------------------------------------------------------------
    // copyFile
    // -------------------------------------------------------------------------

    @Test fun `copyFile composes remoteName colon fromPath and toPath`() = runTest {
        repo.copyFile("gdrive", "source.txt", "backup/source.txt")

        coVerify { engine.copyFile("gdrive:source.txt", "gdrive:backup/source.txt") }
    }

    // -------------------------------------------------------------------------
    // purge
    // -------------------------------------------------------------------------

    @Test fun `purge composes remoteName colon fs and delegates path`() = runTest {
        repo.purge("gdrive", "OldBackups")

        coVerify { engine.purge("gdrive:", "OldBackups") }
    }

    @Test fun `purge passes nested path correctly`() = runTest {
        repo.purge("mys3", "2020/archive")

        coVerify { engine.purge("mys3:", "2020/archive") }
    }

    // -------------------------------------------------------------------------
    // releaseDaemon
    // -------------------------------------------------------------------------

    @Test fun `releaseDaemon delegates to stopDaemonIfIdle`() = runTest {
        repo.releaseDaemon()

        coVerify { engine.stopDaemonIfIdle() }
    }
}
