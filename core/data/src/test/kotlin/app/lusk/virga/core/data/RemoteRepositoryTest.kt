package app.lusk.virga.core.data

import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.database.dao.RemoteDao
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.rclone.RcloneEngine
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RemoteRepositoryTest {

    private val remoteDao = mockk<RemoteDao>(relaxed = true)
    private val engine = mockk<RcloneEngine>()
    private val configManager = mockk<RcloneConfigManager>()

    private lateinit var repo: RemoteRepository

    @BeforeEach fun setUp() {
        repo = RemoteRepository(remoteDao, engine, configManager)
    }

    // --- refresh ---

    @Test fun `refresh replaces all remotes atomically`() = runTest {
        coEvery { engine.listRemotes() } returns listOf(
            Remote("gdrive", "drive"),
            Remote("mys3", "s3"),
        )

        val result = repo.refresh()

        assertThat(result.isSuccess).isTrue()
        coVerify { remoteDao.replaceAll(match { it.size == 2 && it.any { r -> r.name == "gdrive" } }) }
    }

    @Test fun `refresh propagates engine failure as Result failure`() = runTest {
        coEvery { engine.listRemotes() } throws VirgaError.Network("no connection")

        val result = repo.refresh()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(VirgaError.Network::class.java)
    }

    // --- addRemote ---

    @Test fun `addRemote calls engine_createRemote and then refresh on success`() = runTest {
        coEvery { engine.createRemote("new", "drive", any()) } returns Unit
        coEvery { engine.listRemotes() } returns listOf(Remote("new", "drive"))

        val result = repo.addRemote("new", "drive", mapOf("client_id" to "abc"))

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.createRemote("new", "drive", mapOf("client_id" to "abc")) }
        coVerify { remoteDao.replaceAll(match { it.size == 1 && it[0].name == "new" }) }
    }

    @Test fun `addRemote returns failure and skips refresh when engine fails`() = runTest {
        coEvery { engine.createRemote(any(), any(), any()) } throws
            VirgaError.Rclone(message = "duplicate")

        val result = repo.addRemote("dup", "drive", emptyMap())

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { remoteDao.replaceAll(any()) }
    }

    // --- deleteRemote ---

    @Test fun `deleteRemote calls engine then removes from dao`() = runTest {
        coEvery { engine.deleteRemote("old") } returns Unit

        val result = repo.deleteRemote("old")

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.deleteRemote("old") }
        coVerify { remoteDao.deleteByName("old") }
    }

    @Test fun `deleteRemote returns failure and skips dao when engine fails`() = runTest {
        coEvery { engine.deleteRemote(any()) } throws
            VirgaError.Rclone(message = "not found")

        val result = repo.deleteRemote("ghost")

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { remoteDao.deleteByName(any()) }
    }

    // --- importConfig ---

    @Test fun `importConfig returns failure for blank content without calling engine`() = runTest {
        val result = repo.importConfig("   ")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(VirgaError.Rclone::class.java)
        coVerify(exactly = 0) { engine.importConfig(any()) }
    }

    @Test fun `importConfig calls engine and then refresh on success`() = runTest {
        coEvery { engine.importConfig(any()) } returns Unit
        coEvery { engine.listRemotes() } returns listOf(Remote("imported", "drive"))

        val result = repo.importConfig("[imported]\ntype=drive\n")

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.importConfig("[imported]\ntype=drive\n") }
    }

    // --- exportConfig ---

    @Test fun `exportConfig delegates to configManager`() = runTest {
        coEvery { configManager.exportPlaintext() } returns "[gdrive]\ntype=drive\n"

        val content = repo.exportConfig()

        assertThat(content).isEqualTo("[gdrive]\ntype=drive\n")
    }

    @Test fun `exportConfig returns empty string when no config exists`() = runTest {
        coEvery { configManager.exportPlaintext() } returns ""

        assertThat(repo.exportConfig()).isEmpty()
    }
}
