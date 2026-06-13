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

    /**
     * KEEP_BOTH leaves both .conflictN files on the remote, so the same conflict is
     * re-detected with identical evidence each bisync. The resolved row must survive
     * re-detection — neither pruned nor reset to unresolved — or the conflict resurrects.
     */
    @Test
    fun reDetect_resolvedKeepBoth_withSameEvidence_staysResolved() = runTest {
        val dao = db.conflictDao()
        val taskId = seedTask()
        val detected = listOf(conflict(taskId, v1Size = 10, v2Size = 20))
        dao.pruneResolvedAndUpsert(taskId, detected)
        val id = dao.observeUnresolved().first().single().id
        dao.markResolved(id) // user chose KEEP_BOTH

        // Next bisync re-detects the same files+sizes.
        dao.pruneResolvedAndUpsert(taskId, detected)

        assertThat(dao.getById(id)).isNotNull()
        assertThat(dao.getById(id)!!.resolved).isTrue() // still resolved
        assertThat(dao.observeUnresolved().first()).isEmpty() // not resurrected
    }

    /** Re-detection with changed evidence (size/path) is a genuinely new conflict. */
    @Test
    fun reDetect_resolved_withChangedEvidence_flipsToUnresolved() = runTest {
        val dao = db.conflictDao()
        val taskId = seedTask()
        dao.pruneResolvedAndUpsert(taskId, listOf(conflict(taskId, v1Size = 10, v2Size = 20)))
        val id = dao.observeUnresolved().first().single().id
        dao.markResolved(id)

        // Same basePath, but the variant sizes changed -> new conflict.
        dao.pruneResolvedAndUpsert(taskId, listOf(conflict(taskId, v1Size = 99, v2Size = 20)))

        assertThat(dao.getById(id)!!.resolved).isFalse()
        assertThat(dao.observeUnresolved().first()).hasSize(1)
    }

    /** KEEP_VARIANT_1/2 removes a file, so the conflict stops being detected and the
     *  now-stale resolved row must be pruned (its basePath is absent from the new set). */
    @Test
    fun reDetect_resolvedAbsentFromDetection_isPruned() = runTest {
        val dao = db.conflictDao()
        val taskId = seedTask()
        dao.pruneResolvedAndUpsert(taskId, listOf(conflict(taskId, v1Size = 10, v2Size = 20)))
        val id = dao.observeUnresolved().first().single().id
        dao.markResolved(id)

        // A different conflict is detected; the resolved basePath is gone.
        val other = conflict(taskId, v1Size = 1, v2Size = 2).copy(
            basePath = "Docs/other.txt",
            variant1Path = "Docs/other.txt.conflict1",
            variant2Path = "Docs/other.txt.conflict2",
        )
        dao.pruneResolvedAndUpsert(taskId, listOf(other))

        assertThat(dao.getById(id)).isNull() // stale resolved row pruned
        assertThat(dao.observeUnresolved().first()).hasSize(1) // only the new one
    }

    /** A brand-new detected conflict is inserted unresolved. */
    @Test
    fun reDetect_brandNewConflict_isInsertedUnresolved() = runTest {
        val dao = db.conflictDao()
        val taskId = seedTask()

        dao.pruneResolvedAndUpsert(taskId, listOf(conflict(taskId, v1Size = 5, v2Size = 6)))

        val rows = dao.observeUnresolved().first()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().resolved).isFalse()
    }
}
