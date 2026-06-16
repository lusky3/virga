package app.lusk.virga.core.data

import app.lusk.virga.core.common.model.LifetimeStats
import app.lusk.virga.core.common.model.RemoteQuota
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.database.dao.AppStatsDao
import app.lusk.virga.core.database.dao.SyncRunDao
import app.lusk.virga.core.database.entity.AppStatsEntity
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StatsRepositoryTest {

    private val dao = mockk<AppStatsDao>(relaxed = true)
    private val runDao = mockk<SyncRunDao>(relaxed = true)
    private val remoteRepo = mockk<RemoteRepository>(relaxed = true)
    private lateinit var repo: StatsRepository

    @BeforeEach
    fun setUp() {
        repo = StatsRepository(dao, runDao, remoteRepo)
    }

    // ---------------------------------------------------------------------------
    // recordRun — directional byte buckets (full explicit coVerify)
    // ---------------------------------------------------------------------------

    @Test
    fun `recordRun UPLOAD puts bytes in up arg and zeroes down and twoWay`() = runTest {
        repo.recordRun(
            direction = SyncDirection.UPLOAD,
            bytesTransferred = 500L,
            filesTransferred = 3,
            success = true,
            durationMs = 1_000L,
            finishedAtEpochMs = 86_400_000L,
        )

        coVerify {
            dao.record(
                successDelta = 1,
                failDelta = 0,
                files = 3L,
                bytes = 500L,
                up = 500L,
                down = 0L,
                twoWay = 0L,
                durationMs = 1_000L,
                nowEpochMs = 86_400_000L,
                syncDayEpochDay = 1L,
                isSuccess = true,
            )
        }
    }

    @Test
    fun `recordRun DOWNLOAD puts bytes in down arg and zeroes up and twoWay`() = runTest {
        repo.recordRun(
            direction = SyncDirection.DOWNLOAD,
            bytesTransferred = 1024L,
            filesTransferred = 2,
            success = true,
            durationMs = 500L,
            finishedAtEpochMs = 172_800_000L,
        )

        coVerify {
            dao.record(
                successDelta = 1,
                failDelta = 0,
                files = 2L,
                bytes = 1024L,
                up = 0L,
                down = 1024L,
                twoWay = 0L,
                durationMs = 500L,
                nowEpochMs = 172_800_000L,
                syncDayEpochDay = 2L,
                isSuccess = true,
            )
        }
    }

    @Test
    fun `recordRun BISYNC puts bytes in twoWay arg and zeroes up and down`() = runTest {
        repo.recordRun(
            direction = SyncDirection.BISYNC,
            bytesTransferred = 2048L,
            filesTransferred = 10,
            success = true,
            durationMs = 3_000L,
            finishedAtEpochMs = 259_200_000L,
        )

        coVerify {
            dao.record(
                successDelta = 1,
                failDelta = 0,
                files = 10L,
                bytes = 2048L,
                up = 0L,
                down = 0L,
                twoWay = 2048L,
                durationMs = 3_000L,
                nowEpochMs = 259_200_000L,
                syncDayEpochDay = 3L,
                isSuccess = true,
            )
        }
    }

    @Test
    fun `recordRun bytes arg always equals bytesTransferred regardless of direction`() = runTest {
        // DOWNLOAD direction: bytes must equal bytesTransferred, not just the directional bucket.
        repo.recordRun(
            direction = SyncDirection.DOWNLOAD,
            bytesTransferred = 9_999L,
            filesTransferred = 1,
            success = true,
            durationMs = 100L,
            finishedAtEpochMs = 86_400_000L,
        )

        coVerify {
            dao.record(
                successDelta = 1,
                failDelta = 0,
                files = 1L,
                bytes = 9_999L,
                up = 0L,
                down = 9_999L,
                twoWay = 0L,
                durationMs = 100L,
                nowEpochMs = 86_400_000L,
                syncDayEpochDay = 1L,
                isSuccess = true,
            )
        }
    }

    // ---------------------------------------------------------------------------
    // recordRun — success/failure deltas (full explicit coVerify)
    // ---------------------------------------------------------------------------

    @Test
    fun `recordRun success=true passes successDelta=1 and failDelta=0`() = runTest {
        repo.recordRun(
            direction = SyncDirection.UPLOAD,
            bytesTransferred = 100L,
            filesTransferred = 1,
            success = true,
            durationMs = 100L,
            finishedAtEpochMs = 86_400_000L,
        )

        coVerify {
            dao.record(
                successDelta = 1,
                failDelta = 0,
                files = 1L,
                bytes = 100L,
                up = 100L,
                down = 0L,
                twoWay = 0L,
                durationMs = 100L,
                nowEpochMs = 86_400_000L,
                syncDayEpochDay = 1L,
                isSuccess = true,
            )
        }
    }

    @Test
    fun `recordRun success=false passes successDelta=0 and failDelta=1 and isSuccess=false`() = runTest {
        repo.recordRun(
            direction = SyncDirection.UPLOAD,
            bytesTransferred = 0L,
            filesTransferred = 0,
            success = false,
            durationMs = 200L,
            finishedAtEpochMs = 86_400_000L,
        )

        coVerify {
            dao.record(
                successDelta = 0,
                failDelta = 1,
                files = 0L,
                bytes = 0L,
                up = 0L,
                down = 0L,
                twoWay = 0L,
                durationMs = 200L,
                nowEpochMs = 86_400_000L,
                syncDayEpochDay = 1L,
                isSuccess = false,
            )
        }
    }

    // ---------------------------------------------------------------------------
    // recordRun — timestamp derivation (full explicit coVerify)
    // ---------------------------------------------------------------------------

    @Test
    fun `recordRun syncDayEpochDay equals finishedAtEpochMs divided by 86_400_000`() = runTest {
        // Day 5 plus one hour = 5 * 86_400_000 + 3_600_000; integer-divide by 86_400_000 = 5.
        val finishedAt = 5L * 86_400_000L + 3_600_000L
        repo.recordRun(
            direction = SyncDirection.UPLOAD,
            bytesTransferred = 1L,
            filesTransferred = 1,
            success = true,
            durationMs = 100L,
            finishedAtEpochMs = finishedAt,
        )

        coVerify {
            dao.record(
                successDelta = 1,
                failDelta = 0,
                files = 1L,
                bytes = 1L,
                up = 1L,
                down = 0L,
                twoWay = 0L,
                durationMs = 100L,
                nowEpochMs = finishedAt,
                syncDayEpochDay = 5L,
                isSuccess = true,
            )
        }
    }

    @Test
    fun `recordRun nowEpochMs equals finishedAtEpochMs exactly`() = runTest {
        val finishedAt = 123_456_789L
        repo.recordRun(
            direction = SyncDirection.UPLOAD,
            bytesTransferred = 1L,
            filesTransferred = 1,
            success = true,
            durationMs = 100L,
            finishedAtEpochMs = finishedAt,
        )

        coVerify {
            dao.record(
                successDelta = 1,
                failDelta = 0,
                files = 1L,
                bytes = 1L,
                up = 1L,
                down = 0L,
                twoWay = 0L,
                durationMs = 100L,
                nowEpochMs = 123_456_789L,
                syncDayEpochDay = 1L,          // 123_456_789 / 86_400_000 = 1
                isSuccess = true,
            )
        }
    }

    // ---------------------------------------------------------------------------
    // stats flow — entity → domain mapping
    // ---------------------------------------------------------------------------

    @Test
    fun `stats flow maps AppStatsEntity fields to matching LifetimeStats domain object`() = runTest {
        val entity = AppStatsEntity(
            id = 0,
            firstSyncEpochMs = 1_000_000L,
            totalRuns = 42L,
            successfulRuns = 40L,
            failedRuns = 2L,
            totalFilesTransferred = 300L,
            totalBytesTransferred = 50_000L,
            bytesUploaded = 30_000L,
            bytesDownloaded = 15_000L,
            bytesTwoWay = 5_000L,
            totalSyncMillis = 600_000L,
            largestRunBytes = 10_000L,
            longestRunMillis = 120_000L,
            currentStreakDays = 7,
            longestStreakDays = 14,
        )
        every { dao.observe() } returns flowOf(entity)

        // Build the repo AFTER stubbing observe() — `stats` captures the flow once at construction.
        val result = StatsRepository(dao, runDao, remoteRepo).stats.first()

        assertThat(result.firstSyncEpochMs).isEqualTo(1_000_000L)
        assertThat(result.totalRuns).isEqualTo(42L)
        assertThat(result.successfulRuns).isEqualTo(40L)
        assertThat(result.failedRuns).isEqualTo(2L)
        assertThat(result.totalFilesTransferred).isEqualTo(300L)
        assertThat(result.totalBytesTransferred).isEqualTo(50_000L)
        assertThat(result.bytesUploaded).isEqualTo(30_000L)
        assertThat(result.bytesDownloaded).isEqualTo(15_000L)
        assertThat(result.bytesTwoWay).isEqualTo(5_000L)
        assertThat(result.totalSyncMillis).isEqualTo(600_000L)
        assertThat(result.largestRunBytes).isEqualTo(10_000L)
        assertThat(result.longestRunMillis).isEqualTo(120_000L)
        assertThat(result.currentStreakDays).isEqualTo(7)
        assertThat(result.longestStreakDays).isEqualTo(14)
    }

    @Test
    fun `stats flow maps null entity to empty LifetimeStats`() = runTest {
        every { dao.observe() } returns flowOf(null)

        // Build the repo AFTER stubbing observe() — `stats` captures the flow once at construction.
        val result = StatsRepository(dao, runDao, remoteRepo).stats.first()

        assertThat(result).isEqualTo(LifetimeStats())
    }

    @Test
    fun `stats flow null entity produces null firstSyncEpochMs`() = runTest {
        every { dao.observe() } returns flowOf(null)

        // Build the repo AFTER stubbing observe() — `stats` captures the flow once at construction.
        val result = StatsRepository(dao, runDao, remoteRepo).stats.first()

        assertThat(result.firstSyncEpochMs).isNull()
    }

    @Test
    fun `stats flow null entity produces all-zero counters`() = runTest {
        every { dao.observe() } returns flowOf(null)

        // Build the repo AFTER stubbing observe() — `stats` captures the flow once at construction.
        val result = StatsRepository(dao, runDao, remoteRepo).stats.first()

        assertThat(result.totalRuns).isEqualTo(0L)
        assertThat(result.totalBytesTransferred).isEqualTo(0L)
    }

    // ---------------------------------------------------------------------------
    // reset
    // ---------------------------------------------------------------------------

    @Test
    fun `reset calls dao clear`() = runTest {
        repo.reset()

        coVerify { dao.clear() }
    }

    // ---------------------------------------------------------------------------
    // fetchQuota — best-effort network call
    // ---------------------------------------------------------------------------

    @Test
    fun `fetchQuota returns quota for each named remote`() = runTest {
        coEvery { remoteRepo.about("gdrive") } returns Result.success(
            RemoteQuota(total = 15_000_000_000L, used = 5_000_000_000L, free = 10_000_000_000L)
        )

        val result = repo.fetchQuota(listOf("gdrive"))

        assertThat(result).hasSize(1)
        assertThat(result[0].remoteName).isEqualTo("gdrive")
        assertThat(result[0].total).isEqualTo(15_000_000_000L)
        assertThat(result[0].free).isEqualTo(10_000_000_000L)
    }

    @Test
    fun `fetchQuota sets null fields when about fails`() = runTest {
        coEvery { remoteRepo.about("broken") } returns Result.failure(RuntimeException("network error"))

        val result = repo.fetchQuota(listOf("broken"))

        assertThat(result).hasSize(1)
        assertThat(result[0].remoteName).isEqualTo("broken")
        assertThat(result[0].total).isNull()
        assertThat(result[0].free).isNull()
    }
}
