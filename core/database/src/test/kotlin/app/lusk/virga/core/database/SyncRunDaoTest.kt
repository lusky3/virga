package app.lusk.virga.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.database.dao.DayBucketRow
import app.lusk.virga.core.database.dao.RemoteStatRow
import app.lusk.virga.core.database.dao.TaskStatRow
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * In-memory Room tests for [app.lusk.virga.core.database.dao.SyncRunDao], focused on
 * [app.lusk.virga.core.database.dao.SyncRunDao.failInterruptedRuns] — the startup
 * reconciliation that closes runs orphaned RUNNING by process death.
 */
@RunWith(RobolectricTestRunner::class)
class SyncRunDaoTest {

    private lateinit var db: VirgaDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            VirgaDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** Runs FK->sync_tasks, so a task row is required. */
    private suspend fun seedTask(): Long {
        db.remoteDao().upsert(RemoteEntity(name = "gd", type = "drive"))
        return db.syncTaskDao().insert(
            SyncTaskEntity(
                name = "t",
                sourcePath = "/x",
                remoteName = "gd",
                remotePath = "",
                direction = SyncDirection.UPLOAD,
                intervalMinutes = null,
            ),
        )
    }

    private suspend fun insertRun(taskId: Long, startedAt: Long, status: SyncStatus): Long =
        db.syncRunDao().insert(
            SyncRunEntity(taskId = taskId, startedAtEpochMs = startedAt, status = status),
        )

    @Test
    fun failInterruptedRuns_flipsOnlyRunningRowsStartedBeforeBoundary() = runTest {
        val dao = db.syncRunDao()
        val taskId = seedTask()
        val boundary = 1_000L

        val staleRunning = insertRun(taskId, startedAt = 500, status = SyncStatus.RUNNING)
        val freshRunning = insertRun(taskId, startedAt = 1_500, status = SyncStatus.RUNNING)
        val finished = insertRun(taskId, startedAt = 400, status = SyncStatus.SUCCESS)

        val affected = dao.failInterruptedRuns(
            now = 2_000,
            message = "Interrupted",
            startedBefore = boundary,
        )

        assertThat(affected).isEqualTo(1)

        val runs = dao.observeRecent().first().associateBy { it.id }
        // Stale RUNNING started before the boundary -> FAILED, finalized, error counted.
        val stale = runs.getValue(staleRunning)
        assertThat(stale.status).isEqualTo(SyncStatus.FAILED)
        assertThat(stale.endedAtEpochMs).isEqualTo(2_000)
        assertThat(stale.errorCount).isEqualTo(1)
        assertThat(stale.errorMessage).isEqualTo("Interrupted")

        // Fresh RUNNING started after the boundary -> untouched (a genuinely active run).
        val fresh = runs.getValue(freshRunning)
        assertThat(fresh.status).isEqualTo(SyncStatus.RUNNING)
        assertThat(fresh.endedAtEpochMs).isNull()

        // Already-finished run -> untouched.
        assertThat(runs.getValue(finished).status).isEqualTo(SyncStatus.SUCCESS)
    }

    @Test
    fun failInterruptedRuns_returnsZeroWhenNothingStuck() = runTest {
        val dao = db.syncRunDao()
        val taskId = seedTask()
        insertRun(taskId, startedAt = 400, status = SyncStatus.SUCCESS)

        val affected = dao.failInterruptedRuns(now = 2_000, message = "x", startedBefore = 1_000)

        assertThat(affected).isEqualTo(0)
    }

    @Test
    fun observeRemoteStats_groupsByRemoteAndAggregates() = runTest {
        val dao = db.syncRunDao()
        val taskId = seedTask()
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = 1, status = SyncStatus.SUCCESS,
            remoteName = "gdrive", bytesTransferred = 100, filesTransferred = 2))
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = 2, status = SyncStatus.FAILED,
            remoteName = "gdrive", bytesTransferred = 0, filesTransferred = 0))
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = 3, status = SyncStatus.SUCCESS,
            remoteName = "s3", bytesTransferred = 500, filesTransferred = 10))

        val rows = dao.observeRemoteStats().first().sortedBy { it.remoteName }
        assertThat(rows).hasSize(2)
        val gdrive = rows[0]
        assertThat(gdrive.remoteName).isEqualTo("gdrive")
        assertThat(gdrive.totalRuns).isEqualTo(2)
        assertThat(gdrive.successRuns).isEqualTo(1)
        assertThat(gdrive.bytes).isEqualTo(100)
        val s3 = rows[1]
        assertThat(s3.remoteName).isEqualTo("s3")
        assertThat(s3.totalRuns).isEqualTo(1)
        assertThat(s3.successRuns).isEqualTo(1)
        assertThat(s3.bytes).isEqualTo(500)
    }

    @Test
    fun observeTaskStats_groupsByTaskAndAggregates() = runTest {
        val dao = db.syncRunDao()
        val taskId1 = seedTask()
        db.remoteDao().upsert(RemoteEntity(name = "s3", type = "s3"))
        val taskId2 = db.syncTaskDao().insert(
            SyncTaskEntity(
                name = "t2", sourcePath = "/y", remoteName = "s3",
                remotePath = "", direction = SyncDirection.DOWNLOAD, intervalMinutes = null,
            ),
        )
        dao.insert(SyncRunEntity(taskId = taskId1, startedAtEpochMs = 1, status = SyncStatus.SUCCESS,
            bytesTransferred = 200, filesTransferred = 5))
        dao.insert(SyncRunEntity(taskId = taskId2, startedAtEpochMs = 2, status = SyncStatus.FAILED,
            bytesTransferred = 0, filesTransferred = 0))

        val rows = dao.observeTaskStats().first()
        assertThat(rows).hasSize(2)
        val t1 = rows.first { it.taskId == taskId1 }
        assertThat(t1.totalRuns).isEqualTo(1)
        assertThat(t1.successRuns).isEqualTo(1)
        assertThat(t1.bytes).isEqualTo(200)
        val t2 = rows.first { it.taskId == taskId2 }
        assertThat(t2.totalRuns).isEqualTo(1)
        assertThat(t2.successRuns).isEqualTo(0)
    }

    @Test
    fun observeDailyBuckets_groupsByDayAndFiltersOldRows() = runTest {
        val dao = db.syncRunDao()
        val taskId = seedTask()
        val dayMs = 86_400_000L
        val today = System.currentTimeMillis() / dayMs * dayMs
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = today,
            status = SyncStatus.SUCCESS, bytesTransferred = 100))
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = today - 5 * dayMs,
            status = SyncStatus.SUCCESS, bytesTransferred = 50))
        // Outside 30-day window
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = today - 40 * dayMs,
            status = SyncStatus.SUCCESS, bytesTransferred = 999))

        val sinceMs = today - 30 * dayMs
        val rows = dao.observeDailyBuckets(sinceMs).first()
        assertThat(rows).hasSize(2)
        val todayRow = rows.first { it.day == today / dayMs }
        assertThat(todayRow.bytes).isEqualTo(100)
    }

    @Test
    fun sumMeteredBytesFrom_sumsOnlyMeteredRowsAfterCutoff() = runTest {
        val dao = db.syncRunDao()
        val taskId = seedTask()
        val cutoffMs = 1_000L

        // Metered run after cutoff — should be counted.
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = 2_000L,
            status = SyncStatus.SUCCESS, bytesTransferred = 1024L, metered = true))
        // Metered run before cutoff — should be excluded.
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = 500L,
            status = SyncStatus.SUCCESS, bytesTransferred = 4096L, metered = true))
        // Non-metered run after cutoff — should be excluded.
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = 3_000L,
            status = SyncStatus.SUCCESS, bytesTransferred = 2048L, metered = false))

        val result = dao.sumMeteredBytesFrom(cutoffMs).first()

        assertThat(result).isEqualTo(1024L)
    }

    @Test
    fun sumMeteredBytesFrom_returnsNullWhenNoMatchingRows() = runTest {
        val dao = db.syncRunDao()
        val taskId = seedTask()
        // Only non-metered run — sum should be null (SQLite SUM returns null for empty set).
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = 2_000L,
            status = SyncStatus.SUCCESS, bytesTransferred = 512L, metered = false))

        val result = dao.sumMeteredBytesFrom(1_000L).first()

        assertThat(result).isNull()
    }

    @Test
    fun deleteByRemoteName_removesOnlyMatchingRows() = runTest {
        val dao = db.syncRunDao()
        val taskId = seedTask()
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = 1, status = SyncStatus.SUCCESS,
            remoteName = "gdrive"))
        dao.insert(SyncRunEntity(taskId = taskId, startedAtEpochMs = 2, status = SyncStatus.SUCCESS,
            remoteName = "s3"))

        dao.deleteByRemoteName("gdrive")

        val remaining = dao.observeRecent().first()
        assertThat(remaining).hasSize(1)
        assertThat(remaining[0].remoteName).isEqualTo("s3")
    }
}
