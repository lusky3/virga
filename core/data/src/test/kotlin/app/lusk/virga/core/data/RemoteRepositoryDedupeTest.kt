package app.lusk.virga.core.data

import androidx.room.withTransaction
import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.database.VirgaDatabase
import app.lusk.virga.core.database.dao.RemoteDao
import app.lusk.virga.core.database.dao.SyncTaskDao
import app.lusk.virga.core.rclone.RcloneEngine
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [RemoteRepository.dedupe] — kept separate to avoid exceeding the 500-line
 * file-size limit in [RemoteRepositoryTest].
 *
 * The 1 missing codecov line is the dedupe delegation path. Both the success and
 * failure branches are exercised here to close the partial.
 */
class RemoteRepositoryDedupeTest {

    private val db = mockk<VirgaDatabase>()
    private val remoteDao = mockk<RemoteDao>(relaxed = true)
    private val syncTaskDao = mockk<SyncTaskDao>(relaxed = true)
    private val engine = mockk<RcloneEngine>()
    private val configManager = mockk<RcloneConfigManager>()

    private lateinit var repo: RemoteRepository

    @BeforeEach fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { db.withTransaction(capture(block)) } coAnswers { block.captured.invoke() }
        repo = RemoteRepository(db, remoteDao, syncTaskDao, engine, configManager)
    }

    @Test
    fun `dedupe delegates to engine and returns success`() = runTest {
        coEvery { engine.dedupe("gdrive", "skip") } returns Result.success(Unit)

        val result = repo.dedupe("gdrive")

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.dedupe("gdrive", "skip") }
    }

    @Test
    fun `dedupe propagates engine failure as Result failure`() = runTest {
        coEvery { engine.dedupe("s3", "skip") } returns Result.failure(
            VirgaError.Rclone(message = "directory not found"),
        )

        val result = repo.dedupe("s3")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(VirgaError.Rclone::class.java)
    }

    @Test
    fun `dedupe uses skip as the default dedupeMode`() = runTest {
        coEvery { engine.dedupe(any(), any()) } returns Result.success(Unit)

        repo.dedupe("dropbox")

        coVerify { engine.dedupe("dropbox", "skip") }
    }

    @Test
    fun `dedupe respects a custom dedupeMode when provided`() = runTest {
        coEvery { engine.dedupe("box", "newest") } returns Result.success(Unit)

        val result = repo.dedupe("box", dedupeMode = "newest")

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.dedupe("box", "newest") }
    }
}
