package app.lusk.virga.core.database.dao

import androidx.room.Dao
import androidx.room.Transaction
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import app.lusk.virga.core.database.entity.ConflictEntity
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteDao {
    @Query("SELECT * FROM remotes ORDER BY displayName")
    fun observeAll(): Flow<List<RemoteEntity>>

    @Upsert
    suspend fun upsert(remote: RemoteEntity)

    @Upsert
    suspend fun upsertAll(remotes: List<RemoteEntity>)

    @Query("DELETE FROM remotes WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM remotes")
    suspend fun clear()

    /** Atomically clears all remote rows and inserts [remotes] in one transaction. */
    @Transaction
    suspend fun replaceAll(remotes: List<RemoteEntity>) {
        clear()
        upsertAll(remotes)
    }
}

@Dao
interface SyncTaskDao {
    @Query("SELECT * FROM sync_tasks ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<SyncTaskEntity>>

    @Query("SELECT * FROM sync_tasks WHERE id = :id")
    fun observeById(id: Long): Flow<SyncTaskEntity?>

    @Query("SELECT * FROM sync_tasks WHERE id = :id")
    suspend fun getById(id: Long): SyncTaskEntity?

    @Query("SELECT * FROM sync_tasks WHERE enabled = 1 AND intervalMinutes IS NOT NULL")
    suspend fun getScheduled(): List<SyncTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: SyncTaskEntity): Long

    @Update
    suspend fun update(task: SyncTaskEntity)

    @Delete
    suspend fun delete(task: SyncTaskEntity)
}

@Dao
interface SyncRunDao {
    @Query("SELECT * FROM sync_runs ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<SyncRunEntity>>

    @Query("SELECT * FROM sync_runs WHERE id = :id")
    fun observeById(id: Long): Flow<SyncRunEntity?>

    @Query("SELECT * FROM sync_runs WHERE taskId = :taskId ORDER BY startedAtEpochMs DESC")
    fun observeForTask(taskId: Long): Flow<List<SyncRunEntity>>

    @Insert
    suspend fun insert(run: SyncRunEntity): Long

    @Update
    suspend fun update(run: SyncRunEntity)

    @Query("DELETE FROM sync_runs WHERE startedAtEpochMs < :beforeEpochMs")
    suspend fun pruneOlderThan(beforeEpochMs: Long)

    /**
     * Mark runs still flagged RUNNING as FAILED. A worker killed mid-run (process
     * death / force-stop) can't execute its finally to finalize the row, leaving
     * it stuck RUNNING forever. Called once at startup to reconcile those.
     */
    @Query(
        "UPDATE sync_runs SET status = 'FAILED', endedAtEpochMs = :now, " +
            "errorCount = errorCount + 1, errorMessage = :message WHERE status = 'RUNNING'",
    )
    suspend fun failInterruptedRuns(now: Long, message: String): Int

    @Query("DELETE FROM sync_runs")
    suspend fun deleteAll()
}

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts WHERE resolved = 0 ORDER BY detectedAtEpochMs DESC")
    fun observeUnresolved(): Flow<List<ConflictEntity>>

    @Query("SELECT * FROM conflicts WHERE id = :id")
    suspend fun getById(id: Long): ConflictEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(conflict: ConflictEntity): Long

    @Query(
        """
        UPDATE conflicts SET
            taskId = :taskId,
            variant1Path = :v1Path, variant2Path = :v2Path,
            variant1Size = :v1Size, variant2Size = :v2Size,
            detectedAtEpochMs = :detectedAt, resolved = 0
        WHERE remoteName = :remoteName AND basePath = :basePath
        """,
    )
    suspend fun updateByNaturalKey(
        remoteName: String,
        basePath: String,
        taskId: Long,
        v1Path: String,
        v2Path: String,
        v1Size: Long,
        v2Size: Long,
        detectedAt: Long,
    )

    /**
     * Idempotent on the (remoteName, basePath) natural key. @Upsert updates by
     * PRIMARY KEY, but a re-detected conflict carries id=0, so @Upsert silently
     * no-ops the UPDATE against the existing row (its real id is non-zero).
     * Insert-or-ignore then update-by-natural-key makes re-detection actually
     * refresh the stored variant paths/sizes.
     */
    @Transaction
    suspend fun upsertByNaturalKey(conflict: ConflictEntity) {
        if (insertIgnore(conflict) == -1L) {
            updateByNaturalKey(
                remoteName = conflict.remoteName,
                basePath = conflict.basePath,
                taskId = conflict.taskId,
                v1Path = conflict.variant1Path,
                v2Path = conflict.variant2Path,
                v1Size = conflict.variant1Size,
                v2Size = conflict.variant2Size,
                detectedAt = conflict.detectedAtEpochMs,
            )
        }
    }

    @Transaction
    suspend fun upsertAllByNaturalKey(conflicts: List<ConflictEntity>) {
        conflicts.forEach { upsertByNaturalKey(it) }
    }

    @Query("UPDATE conflicts SET resolved = 1 WHERE id = :id")
    suspend fun markResolved(id: Long)

    @Query("DELETE FROM conflicts WHERE taskId = :taskId AND resolved = 1")
    suspend fun pruneResolved(taskId: Long)
}
