package app.lusk.virga.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus
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
}
