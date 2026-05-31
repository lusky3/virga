package app.lusk.virga.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.lusk.virga.core.database.entity.ConflictEntity
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity
import app.lusk.virga.core.common.model.SyncDirection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * In-memory Room tests for [app.lusk.virga.core.database.dao.ConflictDao],
 * focused on the natural-key upsert (re-detection must update, not no-op) and
 * the resolve/prune lifecycle. Runs under Robolectric (JUnit4) via the vintage
 * engine.
 */
@RunWith(RobolectricTestRunner::class)
class ConflictDaoTest {

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

    /** A task row is required because conflicts FK->sync_tasks with CASCADE. */
    private suspend fun seedTask(): Long {
        db.remoteDao().upsert(RemoteEntity(name = "gd", type = "drive", displayName = "GD"))
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

    private fun conflict(taskId: Long, v1Size: Long, v2Size: Long) = ConflictEntity(
        taskId = taskId,
        remoteName = "gd",
        basePath = "Docs/report.txt",
        variant1Path = "Docs/report.txt.conflict1",
        variant2Path = "Docs/report.txt.conflict2",
        variant1Size = v1Size,
        variant2Size = v2Size,
    )

    @Test
    fun reUpsert_withChangedSizes_updatesExistingRowInsteadOfNoOp() = runTest {
        val dao = db.conflictDao()
        val taskId = seedTask()

        dao.upsertByNaturalKey(conflict(taskId, v1Size = 10, v2Size = 20))
        // Re-detection of the same (remoteName, basePath) with new sizes.
        dao.upsertByNaturalKey(conflict(taskId, v1Size = 111, v2Size = 222))

        val rows = dao.observeUnresolved().first()
        assertThat(rows).hasSize(1) // updated in place, not duplicated
        assertThat(rows.single().variant1Size).isEqualTo(111)
        assertThat(rows.single().variant2Size).isEqualTo(222)
    }

    @Test
    fun markResolved_thenPrune_removesResolvedRow() = runTest {
        val dao = db.conflictDao()
        val taskId = seedTask()
        dao.upsertByNaturalKey(conflict(taskId, v1Size = 1, v2Size = 2))
        val id = dao.observeUnresolved().first().single().id

        dao.markResolved(id)
        assertThat(dao.observeUnresolved().first()).isEmpty()

        dao.pruneResolved(taskId)
        assertThat(dao.getById(id)).isNull()
    }
}
