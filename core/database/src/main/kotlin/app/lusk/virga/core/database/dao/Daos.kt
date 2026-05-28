package app.lusk.virga.core.database.dao

import androidx.room.Dao
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

    @Query("SELECT * FROM sync_runs WHERE taskId = :taskId ORDER BY startedAtEpochMs DESC")
    fun observeForTask(taskId: Long): Flow<List<SyncRunEntity>>

    @Insert
    suspend fun insert(run: SyncRunEntity): Long

    @Update
    suspend fun update(run: SyncRunEntity)

    @Query("DELETE FROM sync_runs WHERE startedAtEpochMs < :beforeEpochMs")
    suspend fun pruneOlderThan(beforeEpochMs: Long)
}

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts WHERE resolved = 0 ORDER BY detectedAtEpochMs DESC")
    fun observeUnresolved(): Flow<List<ConflictEntity>>

    @Query("SELECT * FROM conflicts WHERE id = :id")
    suspend fun getById(id: Long): ConflictEntity?

    @Upsert
    suspend fun upsert(conflict: ConflictEntity)

    @Upsert
    suspend fun upsertAll(conflicts: List<ConflictEntity>)

    @Query("UPDATE conflicts SET resolved = 1 WHERE id = :id")
    suspend fun markResolved(id: Long)

    @Query("DELETE FROM conflicts WHERE taskId = :taskId AND resolved = 1")
    suspend fun pruneResolved(taskId: Long)
}
