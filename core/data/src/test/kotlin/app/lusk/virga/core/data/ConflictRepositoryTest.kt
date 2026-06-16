package app.lusk.virga.core.data

import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.Conflict
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncTask
import app.lusk.virga.core.database.dao.ConflictDao
import app.lusk.virga.core.database.entity.ConflictEntity
import app.lusk.virga.core.rclone.RcloneEngine
import app.lusk.virga.core.data.ConflictType
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConflictRepositoryTest {

    private val conflictDao = mockk<ConflictDao>(relaxed = true)
    private val engine = mockk<RcloneEngine>()

    private lateinit var repo: ConflictRepository

    @BeforeEach fun setUp() {
        repo = ConflictRepository(conflictDao, engine)
    }

    private fun task() = SyncTask(
        id = 7,
        name = "t",
        sourcePath = "/x",
        remoteName = "gd",
        remotePath = "",
        direction = SyncDirection.UPLOAD,
        intervalMinutes = null,
    )

    private fun file(name: String, path: String, size: Long = 0) =
        FileItem(name = name, path = path, isDir = false, size = size, modTimeEpochMs = null)

    // --- detectFor ---

    @Test fun `detectFor groups paired variants and derives base path for nested files`() = runTest {
        coEvery { engine.listDir(any(), any(), any(), any()) } returns listOf(
            file("report.txt.conflict1", "Docs/report.txt.conflict1", size = 10),
            file("report.txt.conflict2", "Docs/report.txt.conflict2", size = 20),
        )
        val captured = slot<List<ConflictEntity>>()
        coEvery { conflictDao.pruneResolvedAndUpsert(any(), capture(captured)) } just Runs

        val result = repo.detectFor(task())

        assertThat(result.getOrNull()).isEqualTo(1)
        val c = captured.captured.single()
        assertThat(c.basePath).isEqualTo("Docs/report.txt")
        assertThat(c.variant1Path).isEqualTo("Docs/report.txt.conflict1")
        assertThat(c.variant2Path).isEqualTo("Docs/report.txt.conflict2")
        assertThat(c.variant1Size).isEqualTo(10)
        assertThat(c.variant2Size).isEqualTo(20)
    }

    @Test fun `detectFor derives base path for a root-level file`() = runTest {
        coEvery { engine.listDir(any(), any(), any(), any()) } returns listOf(
            file("report.txt.conflict1", "report.txt.conflict1"),
            file("report.txt.conflict2", "report.txt.conflict2"),
        )
        val captured = slot<List<ConflictEntity>>()
        coEvery { conflictDao.pruneResolvedAndUpsert(any(), capture(captured)) } just Runs

        repo.detectFor(task())

        assertThat(captured.captured.single().basePath).isEqualTo("report.txt")
    }

    @Test fun `detectFor sorts variants by conflict number`() = runTest {
        // Listing order reversed: conflict2 before conflict1.
        coEvery { engine.listDir(any(), any(), any(), any()) } returns listOf(
            file("a.txt.conflict2", "a.txt.conflict2", size = 22),
            file("a.txt.conflict1", "a.txt.conflict1", size = 11),
        )
        val captured = slot<List<ConflictEntity>>()
        coEvery { conflictDao.pruneResolvedAndUpsert(any(), capture(captured)) } just Runs

        repo.detectFor(task())

        val c = captured.captured.single()
        assertThat(c.variant1Path).isEqualTo("a.txt.conflict1")
        assertThat(c.variant1Size).isEqualTo(11)
        assertThat(c.variant2Size).isEqualTo(22)
    }

    @Test fun `detectFor filters out a lone variant below the two-variant threshold`() = runTest {
        coEvery { engine.listDir(any(), any(), any(), any()) } returns listOf(
            file("alone.txt.conflict1", "alone.txt.conflict1"),
        )
        val captured = slot<List<ConflictEntity>>()
        coEvery { conflictDao.pruneResolvedAndUpsert(any(), capture(captured)) } just Runs

        val result = repo.detectFor(task())

        assertThat(result.getOrNull()).isEqualTo(0)
        assertThat(captured.captured).isEmpty()
    }

    @Test fun `detectFor keeps a group with more than two variants`() = runTest {
        coEvery { engine.listDir(any(), any(), any(), any()) } returns listOf(
            file("m.txt.conflict1", "m.txt.conflict1"),
            file("m.txt.conflict2", "m.txt.conflict2"),
            file("m.txt.conflict3", "m.txt.conflict3"),
        )
        val captured = slot<List<ConflictEntity>>()
        coEvery { conflictDao.pruneResolvedAndUpsert(any(), capture(captured)) } just Runs

        val result = repo.detectFor(task())

        // Recorded as a single conflict; the two lowest-numbered variants are kept.
        assertThat(result.getOrNull()).isEqualTo(1)
        val c = captured.captured.single()
        assertThat(c.variant1Path).isEqualTo("m.txt.conflict1")
        assertThat(c.variant2Path).isEqualTo("m.txt.conflict2")
    }

    @Test fun `detectFor returns failure when the engine listing fails`() = runTest {
        coEvery { engine.listDir(any(), any(), any(), any()) } throws VirgaError.Network("offline")

        val result = repo.detectFor(task())

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(VirgaError.Network::class.java)
        coVerify(exactly = 0) { conflictDao.pruneResolvedAndUpsert(any(), any()) }
    }

    // --- B7: conflictType on detectFor rows ---

    @Test fun `B7 detectFor sets conflictType BISYNC by default`() = runTest {
        coEvery { engine.listDir(any(), any(), any(), any()) } returns listOf(
            file("doc.txt.conflict1", "doc.txt.conflict1"),
            file("doc.txt.conflict2", "doc.txt.conflict2"),
        )
        val captured = slot<List<ConflictEntity>>()
        coEvery { conflictDao.pruneResolvedAndUpsert(any(), capture(captured)) } just Runs

        repo.detectFor(task())

        assertThat(captured.captured.single().conflictType).isEqualTo(ConflictType.BISYNC)
    }

    @Test fun `B7 detectFor accepts explicit conflictType override`() = runTest {
        coEvery { engine.listDir(any(), any(), any(), any()) } returns listOf(
            file("doc.txt.conflict1", "doc.txt.conflict1"),
            file("doc.txt.conflict2", "doc.txt.conflict2"),
        )
        val captured = slot<List<ConflictEntity>>()
        coEvery { conflictDao.pruneResolvedAndUpsert(any(), capture(captured)) } just Runs

        repo.detectFor(task(), conflictType = ConflictType.ONE_WAY)

        assertThat(captured.captured.single().conflictType).isEqualTo(ConflictType.ONE_WAY)
    }

    @Test fun `B7 recordOneWayAdvisory stores a single advisory row with ONE_WAY type`() = runTest {
        val captured = slot<List<ConflictEntity>>()
        coEvery { conflictDao.pruneResolvedAndUpsert(any(), capture(captured)) } just Runs

        val result = repo.recordOneWayAdvisory(task(), differences = 3)

        assertThat(result.isSuccess).isTrue()
        val entity = captured.captured.single()
        assertThat(entity.conflictType).isEqualTo(ConflictType.ONE_WAY)
        assertThat(entity.taskId).isEqualTo(task().id)
        assertThat(entity.remoteName).isEqualTo(task().remoteName)
    }

    // --- resolve ---

    private fun conflict() = Conflict(
        id = 3,
        taskId = 7,
        remoteName = "gd",
        basePath = "Docs/report.txt",
        variant1Path = "Docs/report.txt.conflict1",
        variant2Path = "Docs/report.txt.conflict2",
        variant1Size = 10,
        variant2Size = 20,
    )

    @Test fun `resolve KEEP_VARIANT_1 moves winner onto base and deletes loser`() = runTest {
        // Winner still present in the parent listing -> the move proceeds (not skipped).
        coEvery { engine.listDir("gd:", "Docs", false, any()) } returns listOf(
            file("report.txt.conflict1", "report.txt.conflict1"),
            file("report.txt.conflict2", "report.txt.conflict2"),
        )
        coEvery { engine.moveFile(any(), any()) } just Runs
        coEvery { engine.deleteFile(any(), any()) } just Runs

        val result = repo.resolve(conflict(), ConflictChoice.KEEP_VARIANT_1)

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.moveFile("gd:Docs/report.txt.conflict1", "gd:Docs/report.txt") }
        coVerify { engine.deleteFile("gd:", "Docs/report.txt.conflict2") }
        coVerify { conflictDao.markResolved(3) }
    }

    @Test fun `resolve KEEP_BOTH marks resolved without touching the engine`() = runTest {
        val result = repo.resolve(conflict(), ConflictChoice.KEEP_BOTH)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { engine.moveFile(any(), any()) }
        coVerify(exactly = 0) { engine.deleteFile(any(), any()) }
        coVerify { conflictDao.markResolved(3) }
    }

    /** Partial-failure retry: a prior attempt moved the winner but died before deleting the
     *  loser. The winner is gone and the base exists, so the move is skipped and resolve
     *  still succeeds and marks resolved. */
    @Test fun `resolve treats a completed prior move as already done and skips moving`() = runTest {
        coEvery { engine.listDir("gd:", "Docs", false, any()) } returns listOf(
            // winner (conflict1) absent; base present; loser (conflict2) still around.
            file("report.txt", "report.txt"),
            file("report.txt.conflict2", "report.txt.conflict2"),
        )
        coEvery { engine.deleteFile(any(), any()) } just Runs

        val result = repo.resolve(conflict(), ConflictChoice.KEEP_VARIANT_1)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { engine.moveFile(any(), any()) }
        coVerify { engine.deleteFile("gd:", "Docs/report.txt.conflict2") }
        coVerify { conflictDao.markResolved(3) }
    }

    /** Move succeeded, loser deletion failed: deletion is best-effort, so resolve still
     *  returns success and marks resolved (the lone leftover .conflictN is below the
     *  >=2-variant detection threshold and can't resurrect the conflict). */
    @Test fun `resolve marks resolved even when loser deletion fails`() = runTest {
        // No prior move: winner still present, so the move runs.
        coEvery { engine.listDir("gd:", "Docs", false, any()) } returns listOf(
            file("report.txt.conflict1", "report.txt.conflict1"),
            file("report.txt.conflict2", "report.txt.conflict2"),
        )
        coEvery { engine.moveFile(any(), any()) } just Runs
        coEvery { engine.deleteFile(any(), any()) } throws VirgaError.Rclone(message = "delete failed")

        val result = repo.resolve(conflict(), ConflictChoice.KEEP_VARIANT_1)

        assertThat(result.isSuccess).isTrue()
        coVerify { engine.moveFile("gd:Docs/report.txt.conflict1", "gd:Docs/report.txt") }
        coVerify { conflictDao.markResolved(3) }
    }
}
