package app.lusk.virga.core.data

import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.database.dao.SyncRunDao
import app.lusk.virga.core.database.entity.SyncRunEntity
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SyncHistoryRepository] with a mocked [SyncRunDao], mirroring
 * the DAO-mocking style of [RemoteRepositoryTest]. Covers the start/finish run
 * paths, the startup orphan-RUNNING reconciliation, and the observe mappers.
 */
class SyncHistoryRepositoryTest {

    private val runDao = mockk<SyncRunDao>(relaxed = true)
    private lateinit var repo: SyncHistoryRepository

    @BeforeEach fun setUp() {
        repo = SyncHistoryRepository(runDao)
    }

    // --- startRun ---

    @Test fun `startRun inserts a RUNNING row for the task and returns its id`() = runTest {
        val inserted = slot<SyncRunEntity>()
        coEvery { runDao.insert(capture(inserted)) } returns 42L

        val id = repo.startRun(taskId = 7L)

        assertThat(id).isEqualTo(42L)
        assertThat(inserted.captured.taskId).isEqualTo(7L)
        assertThat(inserted.captured.status).isEqualTo(SyncStatus.RUNNING)
        // A fresh run has no end timestamp yet.
        assertThat(inserted.captured.endedAtEpochMs).isNull()
        // startedAtEpochMs is stamped now (non-zero, positive).
        assertThat(inserted.captured.startedAtEpochMs).isGreaterThan(0L)
    }

    // --- finishRun ---

    @Test fun `finishRun forwards all fields to the targeted UPDATE`() = runTest {
        repo.finishRun(
            runId = 5L,
            status = SyncStatus.SUCCESS,
            filesTransferred = 3,
            bytesTransferred = 1024L,
            errorCount = 0,
            errorMessage = null,
            logPath = "/logs/run5.txt",
        )

        // The repo supplies endedAtEpochMs itself (now) and preserves startedAtEpochMs
        // by NOT passing it — verify the DAO is called with everything else verbatim.
        coVerify {
            runDao.finishRun(
                runId = 5L,
                endedAtEpochMs = match { it > 0L },
                status = SyncStatus.SUCCESS,
                filesTransferred = 3,
                bytesTransferred = 1024L,
                errorCount = 0,
                errorMessage = null,
                logPath = "/logs/run5.txt",
                failedFiles = "",
            )
        }
    }

    @Test fun `finishRun carries failure status and error details`() = runTest {
        repo.finishRun(
            runId = 9L,
            status = SyncStatus.FAILED,
            filesTransferred = 0,
            bytesTransferred = 0L,
            errorCount = 2,
            errorMessage = "boom",
        )

        coVerify {
            runDao.finishRun(
                runId = 9L,
                endedAtEpochMs = any(),
                status = SyncStatus.FAILED,
                filesTransferred = 0,
                bytesTransferred = 0L,
                errorCount = 2,
                errorMessage = "boom",
                logPath = null,
                failedFiles = "",
            )
        }
    }

    @Test fun `finishRun forwards failedFiles to the dao`() = runTest {
        val failedFiles = "path/to/file.txt\tpermission denied"
        repo.finishRun(
            runId = 7L,
            status = SyncStatus.SUCCESS,
            filesTransferred = 5,
            bytesTransferred = 512L,
            errorCount = 1,
            failedFiles = failedFiles,
        )

        coVerify {
            runDao.finishRun(
                runId = 7L,
                endedAtEpochMs = match { it > 0L },
                status = SyncStatus.SUCCESS,
                filesTransferred = 5,
                bytesTransferred = 512L,
                errorCount = 1,
                errorMessage = null,
                logPath = null,
                failedFiles = failedFiles,
            )
        }
    }

    // --- reconcileInterruptedRuns ---

    @Test fun `reconcileInterruptedRuns marks orphan RUNNING rows failed and returns the count`() = runTest {
        val now = slot<Long>()
        val startedBefore = slot<Long>()
        coEvery {
            runDao.failInterruptedRuns(now = capture(now), message = any(), startedBefore = capture(startedBefore))
        } returns 3

        val processStart = 1_000_000L
        val reconciled = repo.reconcileInterruptedRuns(startedBeforeEpochMs = processStart)

        assertThat(reconciled).isEqualTo(3)
        // The boundary is passed through verbatim so runs that started DURING startup
        // (>= processStart) are not mis-marked failed.
        assertThat(startedBefore.captured).isEqualTo(processStart)
        // `now` is the repo's own clock reading, stamped as the end time.
        assertThat(now.captured).isGreaterThan(0L)
    }

    @Test fun `reconcileInterruptedRuns returns zero when nothing was interrupted`() = runTest {
        coEvery { runDao.failInterruptedRuns(any(), any(), any()) } returns 0

        assertThat(repo.reconcileInterruptedRuns(startedBeforeEpochMs = 5_000L)).isEqualTo(0)
    }

    // --- hasSucceeded ---

    @Test fun `hasSucceeded is true when the task has at least one SUCCESS run`() = runTest {
        coEvery { runDao.countSuccessful(11L) } returns 1
        assertThat(repo.hasSucceeded(11L)).isTrue()
    }

    @Test fun `hasSucceeded is false when the task has never succeeded`() = runTest {
        coEvery { runDao.countSuccessful(11L) } returns 0
        assertThat(repo.hasSucceeded(11L)).isFalse()
    }

    // --- pass-through delegations ---

    @Test fun `pruneOlderThan delegates to the dao`() = runTest {
        repo.pruneOlderThan(beforeEpochMs = 999L)
        coVerify { runDao.pruneOlderThan(999L) }
    }

    @Test fun `clearAll delegates to the dao`() = runTest {
        repo.clearAll()
        coVerify { runDao.deleteAll() }
    }

    // --- observe flows (DAO row -> domain mapping) ---

    @Test fun `recentRuns maps dao rows to domain models`() = runTest {
        // `recentRuns` is a property assigned in the constructor, so the dao stub must be
        // in place before the repo is built (the BeforeEach-built repo captured an empty flow).
        coEvery { runDao.observeRecent(any()) } returns flowOf(
            listOf(
                SyncRunEntity(id = 1L, taskId = 2L, startedAtEpochMs = 100L, status = SyncStatus.SUCCESS),
            ),
        )
        val repo = SyncHistoryRepository(runDao)

        val runs = repo.recentRuns.first()

        assertThat(runs).hasSize(1)
        assertThat(runs[0].id).isEqualTo(1L)
        assertThat(runs[0].taskId).isEqualTo(2L)
        assertThat(runs[0].status).isEqualTo(SyncStatus.SUCCESS)
    }

    @Test fun `observeRun maps a present row and passes null through`() = runTest {
        coEvery { runDao.observeById(3L) } returns flowOf(
            SyncRunEntity(id = 3L, taskId = 4L, startedAtEpochMs = 50L, status = SyncStatus.RUNNING),
        )
        coEvery { runDao.observeById(404L) } returns flowOf(null)

        assertThat(repo.observeRun(3L).first()?.id).isEqualTo(3L)
        assertThat(repo.observeRun(404L).first()).isNull()
    }

    @Test fun `runsForTask maps dao rows for the given task`() = runTest {
        coEvery { runDao.observeForTask(8L, any()) } returns flowOf(
            listOf(
                SyncRunEntity(id = 10L, taskId = 8L, startedAtEpochMs = 1L, status = SyncStatus.FAILED),
                SyncRunEntity(id = 11L, taskId = 8L, startedAtEpochMs = 2L, status = SyncStatus.SUCCESS),
            ),
        )

        val runs = repo.runsForTask(8L).first()

        assertThat(runs.map { it.id }).containsExactly(10L, 11L).inOrder()
        assertThat(runs.map { it.status })
            .containsExactly(SyncStatus.FAILED, SyncStatus.SUCCESS).inOrder()
    }
}
