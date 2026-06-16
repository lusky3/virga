package app.lusk.virga.core.database

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncRunPagedDaoTest {

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

    private suspend fun seedTask(name: String = "Photos"): Long {
        db.remoteDao().upsert(RemoteEntity(name = "gd", type = "drive"))
        return db.syncTaskDao().insert(
            SyncTaskEntity(
                name = name,
                sourcePath = "/src",
                remoteName = "gd",
                remotePath = "/dst",
                direction = SyncDirection.UPLOAD,
                intervalMinutes = null,
            ),
        )
    }

    private suspend fun insertRun(taskId: Long, status: SyncStatus = SyncStatus.SUCCESS, remote: String = "gd"): Long =
        db.syncRunDao().insert(
            SyncRunEntity(
                taskId = taskId,
                startedAtEpochMs = System.currentTimeMillis(),
                status = status,
                remoteName = remote,
            ),
        )

    @Test fun `pagedRuns returns all rows when no filter`() = runTest {
        val taskId = seedTask()
        insertRun(taskId)
        insertRun(taskId, SyncStatus.FAILED)

        val pager = TestPager(
            PagingConfig(pageSize = 10),
            db.syncRunDao().pagedRunsWithTask(null, null, ""),
        )
        val page = pager.refresh() as PagingSource.LoadResult.Page
        assertThat(page.data).hasSize(2)
    }

    @Test fun `pagedRuns filters by status`() = runTest {
        val taskId = seedTask()
        insertRun(taskId, SyncStatus.SUCCESS)
        insertRun(taskId, SyncStatus.FAILED)

        val pager = TestPager(
            PagingConfig(pageSize = 10),
            db.syncRunDao().pagedRunsWithTask(null, SyncStatus.FAILED, ""),
        )
        val page = pager.refresh() as PagingSource.LoadResult.Page
        assertThat(page.data).hasSize(1)
        assertThat(page.data.single().status).isEqualTo(SyncStatus.FAILED)
    }

    @Test fun `pagedRuns filters by taskId`() = runTest {
        val task1 = seedTask("Photos")
        db.remoteDao().upsert(RemoteEntity(name = "gd2", type = "drive"))
        val task2 = db.syncTaskDao().insert(
            SyncTaskEntity(
                name = "Videos",
                sourcePath = "/v",
                remoteName = "gd2",
                remotePath = "/dv",
                direction = SyncDirection.UPLOAD,
                intervalMinutes = null,
            ),
        )
        insertRun(task1)
        insertRun(task2)

        val pager = TestPager(
            PagingConfig(pageSize = 10),
            db.syncRunDao().pagedRunsWithTask(task1, null, ""),
        )
        val page = pager.refresh() as PagingSource.LoadResult.Page
        assertThat(page.data).hasSize(1)
        assertThat(page.data.single().taskId).isEqualTo(task1)
    }

    @Test fun `pagedRuns search filters by task name`() = runTest {
        val task1 = seedTask("Photos")
        db.remoteDao().upsert(RemoteEntity(name = "gd3", type = "drive"))
        val task2 = db.syncTaskDao().insert(
            SyncTaskEntity(
                name = "Documents",
                sourcePath = "/doc",
                remoteName = "gd3",
                remotePath = "/d",
                direction = SyncDirection.UPLOAD,
                intervalMinutes = null,
            ),
        )
        insertRun(task1)
        insertRun(task2)

        val pager = TestPager(
            PagingConfig(pageSize = 10),
            db.syncRunDao().pagedRunsWithTask(null, null, "photo"),
        )
        val page = pager.refresh() as PagingSource.LoadResult.Page
        assertThat(page.data).hasSize(1)
        assertThat(page.data.single().taskName).isEqualTo("Photos")
    }

    @Test fun `pagedRuns includes task name in projection`() = runTest {
        val taskId = seedTask("My Task")
        insertRun(taskId)

        val pager = TestPager(
            PagingConfig(pageSize = 10),
            db.syncRunDao().pagedRunsWithTask(null, null, ""),
        )
        val page = pager.refresh() as PagingSource.LoadResult.Page
        assertThat(page.data.single().taskName).isEqualTo("My Task")
    }
}
