package app.lusk.virga.core.database.dao

import androidx.room.Dao
import androidx.room.Transaction
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import app.lusk.virga.core.common.model.SyncStatus
import app.lusk.virga.core.database.entity.ConflictEntity
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.database.entity.SyncRunEntity
import app.lusk.virga.core.database.entity.SyncTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RemoteDao {
    @Query("SELECT * FROM remotes ORDER BY name")
    fun observeAll(): Flow<List<RemoteEntity>>

    @Upsert
    suspend fun upsert(remote: RemoteEntity)

    @Upsert
    suspend fun upsertAll(remotes: List<RemoteEntity>)

    @Query("DELETE FROM remotes WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM remotes")
    suspend fun clear()

    @Query("SELECT name, needsReauth FROM remotes")
    suspend fun getNeedsReauthMap(): List<NeedsReauthRow>

    @Query("UPDATE remotes SET needsReauth = :flag WHERE name = :name")
    suspend fun setNeedsReauth(name: String, flag: Boolean)

    /** Atomically clears all remote rows and inserts [remotes] in one transaction. */
    @Transaction
    suspend fun replaceAll(remotes: List<RemoteEntity>) {
        val reauth = getNeedsReauthMap().associateBy({ it.name }, { it.needsReauth })
        clear()
        upsertAll(remotes.map { it.copy(needsReauth = reauth[it.name] ?: false) })
    }
}

/** Lightweight projection used by [RemoteDao.replaceAll] to carry the flag across a cache rebuild. */
data class NeedsReauthRow(val name: String, val needsReauth: Boolean)

/** Per-remote aggregate projection for stats queries. */
data class RemoteStatRow(
    val remoteName: String,
    val totalRuns: Long,
    val successRuns: Long,
    val bytes: Long,
    val files: Long,
)

/** Per-task aggregate projection for stats queries. */
data class TaskStatRow(
    val taskId: Long,
    val totalRuns: Long,
    val successRuns: Long,
    val bytes: Long,
    val files: Long,
)

/** One epoch-day bucket of bytes transferred, for trend charting. */
data class DayBucketRow(val day: Long, val bytes: Long)

@Dao
interface SyncTaskDao {
    @Query("SELECT * FROM sync_tasks ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<SyncTaskEntity>>

    @Query("SELECT * FROM sync_tasks WHERE id = :id")
    fun observeById(id: Long): Flow<SyncTaskEntity?>

    @Query("SELECT * FROM sync_tasks WHERE id = :id")
    suspend fun getById(id: Long): SyncTaskEntity?

    /** Enabled tasks with any automatic schedule — a polling interval OR a
     *  calendar (weekday) schedule. Used to re-register work after a reboot. */
    @Query("SELECT * FROM sync_tasks WHERE enabled = 1 AND (intervalMinutes IS NOT NULL OR scheduleDaysMask != 0)")
    suspend fun getScheduled(): List<SyncTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: SyncTaskEntity): Long

    @Update
    suspend fun update(task: SyncTaskEntity)

    @Delete
    suspend fun delete(task: SyncTaskEntity)

    /** Deletes all tasks targeting [remoteName] — used to clean up after a remote is removed. */
    @Query("DELETE FROM sync_tasks WHERE remoteName = :remoteName")
    suspend fun deleteByRemoteName(remoteName: String)

    /**
     * Repoints all tasks from [oldName] to [newName] after a remote rename.
     * A plain UPDATE — no schema change, no cascade delete. Tasks are preserved.
     */
    @Query("UPDATE sync_tasks SET remoteName = :newName WHERE remoteName = :oldName")
    suspend fun repointRemoteName(oldName: String, newName: String)
}

@Dao
interface SyncRunDao {
    @Query("SELECT * FROM sync_runs ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<SyncRunEntity>>

    @Query("SELECT * FROM sync_runs WHERE id = :id")
    fun observeById(id: Long): Flow<SyncRunEntity?>

    @Query("SELECT * FROM sync_runs WHERE taskId = :taskId ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeForTask(taskId: Long, limit: Int = 200): Flow<List<SyncRunEntity>>

    @Insert
    suspend fun insert(run: SyncRunEntity): Long

    /**
     * Finalizes a run in place. A targeted UPDATE so the caller needn't re-supply
     * `startedAtEpochMs` — the value stored by `startRun` is preserved instead of being
     * overwritten with a second, later clock reading.
     */
    @Query(
        "UPDATE sync_runs SET endedAtEpochMs = :endedAtEpochMs, status = :status, " +
            "filesTransferred = :filesTransferred, bytesTransferred = :bytesTransferred, " +
            "errorCount = :errorCount, errorMessage = :errorMessage, logPath = :logPath, " +
            "failedFiles = :failedFiles, remoteName = :remoteName, direction = :direction, " +
            "durationMs = :durationMs " +
            "WHERE id = :runId",
    )
    suspend fun finishRun(
        runId: Long,
        endedAtEpochMs: Long,
        status: SyncStatus,
        filesTransferred: Int,
        bytesTransferred: Long,
        errorCount: Int,
        errorMessage: String?,
        logPath: String?,
        failedFiles: String = "",
        remoteName: String = "",
        direction: String = "",
        durationMs: Long = 0,
    )

    @Query("DELETE FROM sync_runs WHERE startedAtEpochMs < :beforeEpochMs")
    suspend fun pruneOlderThan(beforeEpochMs: Long)

    /** Count of prior SUCCESS runs for a task. Used to detect a task's first
     *  successful sync (e.g. a bisync that still needs a --resync baseline). */
    @Query("SELECT COUNT(*) FROM sync_runs WHERE taskId = :taskId AND status = 'SUCCESS'")
    suspend fun countSuccessful(taskId: Long): Int

    /**
     * Mark runs still flagged RUNNING as FAILED. A worker killed mid-run (process
     * death / force-stop) can't execute its finally to finalize the row, leaving
     * it stuck RUNNING forever. Called once at startup to reconcile those.
     */
    @Query(
        "UPDATE sync_runs SET status = 'FAILED', endedAtEpochMs = :now, " +
            "errorCount = errorCount + 1, errorMessage = :message " +
            "WHERE status = 'RUNNING' AND startedAtEpochMs < :startedBefore",
    )
    suspend fun failInterruptedRuns(now: Long, message: String, startedBefore: Long): Int

    @Query(
        "SELECT remoteName, COUNT(*) AS totalRuns, " +
            "SUM(CASE WHEN status='SUCCESS' THEN 1 ELSE 0 END) AS successRuns, " +
            "SUM(bytesTransferred) AS bytes, SUM(filesTransferred) AS files " +
            "FROM sync_runs WHERE remoteName != '' GROUP BY remoteName",
    )
    fun observeRemoteStats(): Flow<List<RemoteStatRow>>

    @Query(
        "SELECT taskId, COUNT(*) AS totalRuns, " +
            "SUM(CASE WHEN status='SUCCESS' THEN 1 ELSE 0 END) AS successRuns, " +
            "SUM(bytesTransferred) AS bytes, SUM(filesTransferred) AS files " +
            "FROM sync_runs GROUP BY taskId",
    )
    fun observeTaskStats(): Flow<List<TaskStatRow>>

    @Query(
        "SELECT (startedAtEpochMs/86400000) AS day, SUM(bytesTransferred) AS bytes " +
            "FROM sync_runs WHERE startedAtEpochMs >= :sinceMs GROUP BY day ORDER BY day",
    )
    fun observeDailyBuckets(sinceMs: Long): Flow<List<DayBucketRow>>

    @Query("DELETE FROM sync_runs WHERE remoteName = :remoteName")
    suspend fun deleteByRemoteName(remoteName: String)

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
            variant1Path = :v1Path, variant2Path = :v2Path,
            variant1Size = :v1Size, variant2Size = :v2Size,
            detectedAtEpochMs = :detectedAt,
            -- Keep the user's resolution (e.g. KEEP_BOTH leaves both .conflictN files on
            -- the remote, so the same conflict is re-detected every bisync). Only the same
            -- files+sizes count as "already resolved"; any changed evidence is a genuinely
            -- new conflict and must reset to unresolved.
            resolved = CASE
                WHEN variant1Path = :v1Path AND variant2Path = :v2Path
                    AND variant1Size = :v1Size AND variant2Size = :v2Size THEN resolved
                ELSE 0
            END
        WHERE taskId = :taskId AND remoteName = :remoteName AND basePath = :basePath
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
     * Idempotent on the (taskId, remoteName, basePath) natural key. @Upsert updates by
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

    /**
     * Atomically replace a task's unresolved-conflict set: drop the resolved rows
     * and upsert the freshly-detected ones in one transaction, so a crash between
     * the two can't leave the task showing zero conflicts when some were just found.
     *
     * Only resolved rows whose basePath is absent from the freshly-detected set are
     * pruned. A KEEP_BOTH resolution leaves both .conflictN files on the remote, so it
     * is re-detected every run; pruning it here (then re-inserting unresolved) would
     * resurrect the conflict forever. Keeping re-detected resolved rows lets the
     * subsequent upsert preserve their resolution via updateByNaturalKey's CASE.
     * KEEP_VARIANT_1/2 remove a file, so those conflicts stop being detected and their
     * resolved rows fall outside the kept set and are correctly pruned.
     */
    @Transaction
    suspend fun pruneResolvedAndUpsert(taskId: Long, conflicts: List<ConflictEntity>) {
        if (conflicts.isEmpty()) {
            // Nothing detected: every resolved row is now stale (its files are gone).
            pruneResolved(taskId)
        } else {
            // SQLite rejects `NOT IN ()`, so the empty case is handled above. Conflict
            // lists are tiny, so the 999-variable bind limit is not a concern.
            pruneResolvedNotIn(taskId, conflicts.map { it.basePath })
            upsertAllByNaturalKey(conflicts)
        }
    }

    @Query("UPDATE conflicts SET resolved = 1 WHERE id = :id")
    suspend fun markResolved(id: Long)

    @Query("DELETE FROM conflicts WHERE taskId = :taskId AND resolved = 1")
    suspend fun pruneResolved(taskId: Long)

    /** Prune resolved rows whose basePath is no longer detected (their files were removed). */
    @Query("DELETE FROM conflicts WHERE taskId = :taskId AND resolved = 1 AND basePath NOT IN (:keptBasePaths)")
    suspend fun pruneResolvedNotIn(taskId: Long, keptBasePaths: List<String>)

    /**
     * Repoints all conflict rows from [oldName] to [newName] after a remote rename.
     * Preserves resolution state and all other columns. Must be called in the same
     * success path as [SyncTaskDao.repointRemoteName].
     */
    @Query("UPDATE conflicts SET remoteName = :newName WHERE remoteName = :oldName")
    suspend fun repointRemoteName(oldName: String, newName: String)
}
