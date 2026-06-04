package app.lusk.virga.core.data

import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.database.dao.RemoteDao
import app.lusk.virga.core.database.dao.SyncTaskDao
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
    private val syncTaskDao = mockk<SyncTaskDao>(relaxed = true)
    private val engine = mockk<RcloneEngine>()
    private val configManager = mockk<RcloneConfigManager>()

    private lateinit var repo: RemoteRepository

    @BeforeEach fun setUp() {
        repo = RemoteRepository(remoteDao, syncTaskDao, engine, configManager)
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

    @Test fun `refresh does NOT clear the cache when the live dump is empty`() = runTest {
        // An empty config/dump (e.g. a config that failed to load) must never wipe
        // the user's configured remotes — replaceAll(emptyList) would delete them.
        coEvery { engine.listRemotes() } returns emptyList()

        val result = repo.refresh()

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { remoteDao.replaceAll(any()) }
    }

    // --- addRemote ---

    @Test fun `addRemote calls engine_createRemote and then refresh on success`() = runTest {
        coEvery { engine.createRemote("new", "drive", any(), any()) } returns Unit
        coEvery { engine.listRemotes() } returns listOf(Remote("new", "drive"))

        val result = repo.addRemote("new", "drive", mapOf("client_id" to "abc"))

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.createRemote("new", "drive", mapOf("client_id" to "abc"), emptySet()) }
        coVerify { remoteDao.replaceAll(match { it.size == 1 && it[0].name == "new" }) }
    }

    @Test fun `addRemote returns failure and skips refresh when engine fails`() = runTest {
        coEvery { engine.createRemote(any(), any(), any(), any()) } throws
            VirgaError.Rclone(message = "duplicate")

        val result = repo.addRemote("dup", "drive", emptyMap())

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { remoteDao.replaceAll(any()) }
    }

    @Test fun `addRemote forwards sensitiveKeys to the engine`() = runTest {
        coEvery { engine.createRemote(any(), any(), any(), any()) } returns Unit
        coEvery { engine.listRemotes() } returns listOf(Remote("sftp1", "sftp"))

        val result = repo.addRemote("sftp1", "sftp", mapOf("pass" to "p"), sensitiveKeys = setOf("pass"))

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.createRemote("sftp1", "sftp", mapOf("pass" to "p"), setOf("pass")) }
    }

    // --- deleteRemote ---

    @Test fun `deleteRemote calls engine then removes from dao`() = runTest {
        coEvery { engine.deleteRemote("old") } returns Unit

        val result = repo.deleteRemote("old")

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.deleteRemote("old") }
        coVerify { remoteDao.deleteByName("old") }
        // Tasks pointing at the deleted remote are cleaned up too.
        coVerify { syncTaskDao.deleteByRemoteName("old") }
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

    // --- addCryptRemote ---

    @Test fun `addCryptRemote delegates to engine_createCryptRemote and then refreshes`() = runTest {
        coEvery { engine.createCryptRemote("enc", "gdrive:vault", "s3cr3t", null) } returns Unit
        coEvery { engine.listRemotes() } returns listOf(Remote("enc", "crypt"))

        val result = repo.addCryptRemote("enc", "gdrive:vault", "s3cr3t", null)

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.createCryptRemote("enc", "gdrive:vault", "s3cr3t", null) }
        coVerify { remoteDao.replaceAll(match { it.any { r -> r.name == "enc" } }) }
    }

    @Test fun `addCryptRemote returns failure and skips refresh when engine fails`() = runTest {
        coEvery { engine.createCryptRemote(any(), any(), any(), any()) } throws
            VirgaError.Rclone(message = "base remote not found")

        val result = repo.addCryptRemote("enc", "ghost:", "pass", null)

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { remoteDao.replaceAll(any()) }
    }

    @Test fun `addCryptRemote forwards salt to engine when provided`() = runTest {
        coEvery { engine.createCryptRemote("enc", "s3:bucket", "pass", "mysalt") } returns Unit
        coEvery { engine.listRemotes() } returns listOf(Remote("enc", "crypt"))

        val result = repo.addCryptRemote("enc", "s3:bucket", "pass", "mysalt")

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.createCryptRemote("enc", "s3:bucket", "pass", "mysalt") }
    }

    // --- exportConfig ---

    @Test fun `testConnectivity delegates to engine`() = runTest {
        coEvery { engine.testConnectivity("gdrive") } returns Result.success(Unit)

        val result = repo.testConnectivity("gdrive")

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.testConnectivity("gdrive") }
    }

    @Test fun `testConnectivity propagates engine failure`() = runTest {
        coEvery { engine.testConnectivity("broken") } returns Result.failure(VirgaError.Network("timeout"))

        val result = repo.testConnectivity("broken")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(VirgaError.Network::class.java)
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
