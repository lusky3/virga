package app.lusk.virga.core.data

import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteProvider
import app.lusk.virga.core.common.model.RemoteQuota
import androidx.room.withTransaction
import app.lusk.virga.core.database.VirgaDatabase
import app.lusk.virga.core.database.dao.ConflictDao
import app.lusk.virga.core.database.dao.RemoteDao
import app.lusk.virga.core.database.dao.SyncTaskDao
import app.lusk.virga.core.database.entity.RemoteEntity
import app.lusk.virga.core.rclone.RcloneEngine
import app.lusk.virga.core.rclone.config.RcloneConfigManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Source of truth for configured remotes. The Room cache drives list UIs; the
 * rclone daemon is the authority and is reconciled into the cache on [refresh].
 */
@Singleton
class RemoteRepository @Inject constructor(
    private val db: VirgaDatabase,
    private val remoteDao: RemoteDao,
    private val syncTaskDao: SyncTaskDao,
    private val conflictDao: ConflictDao,
    private val engine: RcloneEngine,
    private val configManager: RcloneConfigManager,
) {
    /** Configured remotes as domain models (the Room entity stays in this layer). */
    val remotes: Flow<List<Remote>> = remoteDao.observeAll().map { rows ->
        rows.map { Remote(name = it.name, type = it.type, needsReauth = it.needsReauth) }
    }

    /**
     * Pulls the live remote list from rclone and reconciles the cached rows.
     *
     * FAIL-SAFE: an empty dump never clears the cache. `config/dump` can come back
     * empty for non-user reasons — a config that failed to load/decrypt, or a
     * daemon queried before its config settled — and `replaceAll(emptyList)` would
     * then *delete the user's configured accounts* (a credential-loss bug we hit in
     * testing). User-initiated removals go through [deleteRemote] directly, so
     * refresh only needs to add/update from a non-empty dump; it must never treat
     * "I read zero remotes" as "the user has zero remotes."
     */
    suspend fun refresh(): Result<Unit> = runCatching {
        val live = engine.listRemotes()
        if (live.isEmpty()) return@runCatching
        remoteDao.replaceAll(
            live.map { RemoteEntity(name = it.name, type = it.type) },
        )
    }

    /**
     * Sets (or clears) the re-auth flag for [name]. Used by [SyncWorker] on auth failure
     * and by the VM after a successful re-authentication clears the flag.
     */
    suspend fun setNeedsReauth(name: String, flag: Boolean) {
        remoteDao.setNeedsReauth(name, flag)
    }

    suspend fun addRemote(
        name: String,
        type: String,
        params: Map<String, String>,
        sensitiveKeys: Set<String> = emptySet(),
    ): Result<Unit> =
        runCatching { engine.createRemote(name, type, params, sensitiveKeys) }
            .mapCatching { refresh().getOrThrow() }

    /**
     * Creates a `crypt:` remote wrapping [baseRemoteSpec]. Delegates password
     * obscuring to rclone (via [RcloneEngine.createCryptRemote]) — no plaintext
     * password material is stored or logged by this layer.
     */
    suspend fun addCryptRemote(
        name: String,
        baseRemoteSpec: String,
        password: String,
        salt: String?,
    ): Result<Unit> =
        runCatching { engine.createCryptRemote(name, baseRemoteSpec, password, salt) }
            .mapCatching { refresh().getOrThrow() }

    /**
     * Updates an existing remote's config — only the changed [params] are sent.
     * [sensitiveKeys] is the subset that rclone should obscure before writing.
     * Refreshes the cache on success.
     */
    suspend fun updateRemote(
        name: String,
        params: Map<String, String>,
        sensitiveKeys: Set<String> = emptySet(),
    ): Result<Unit> =
        runCatching { engine.updateRemote(name, params, sensitiveKeys) }
            .mapCatching { refresh().getOrThrow() }

    /**
     * Fetches the current raw config params for [name] from rclone.
     * Returns [Result.failure] when the remote is not found or the engine fails.
     * Callers must not surface password values in the UI.
     */
    suspend fun getRemoteParams(name: String): Result<Map<String, String>> =
        runCatching { engine.getRemoteParams(name) }

    suspend fun deleteRemote(name: String): Result<Unit> =
        runCatching {
            // Network I/O stays OUTSIDE the transaction. The two cache deletes go in one
            // transaction so process death can't land between them: deleting the remote
            // row but leaving its task rows would orphan them, and they'd keep firing
            // failing syncs forever.
            engine.deleteRemote(name)
            db.withTransaction {
                remoteDao.deleteByName(name)
                // Tasks pointing at the removed remote can no longer function — drop them
                // so they don't linger as broken rows. Their scheduled WorkManager jobs
                // self-cancel on the next run (SyncWorker cancels work for a missing task).
                syncTaskDao.deleteByRemoteName(name)
            }
        }

    /**
     * Renames [oldName] to [newName] in the rclone config, then repoints all
     * [SyncTaskEntity] and [ConflictEntity] rows so neither tasks nor unresolved
     * conflicts are dropped (unlike delete, which cascades). The DB repoint only
     * runs after the engine rename succeeds. Refreshes the remote cache on success.
     */
    suspend fun renameRemote(oldName: String, newName: String): Result<Unit> =
        runCatching { engine.renameRemote(oldName, newName) }
            .mapCatching {
                // Repoint both tables atomically: a failure between them would leave
                // conflicts dangling at the old name while sync_tasks already moved.
                db.withTransaction {
                    syncTaskDao.repointRemoteName(oldName, newName)
                    conflictDao.repointRemoteName(oldName, newName)
                }
                refresh().getOrThrow()
            }

    suspend fun importConfig(confContent: String): Result<Unit> {
        if (confContent.isBlank()) {
            return Result.failure(VirgaError.Rclone(message = "Empty config"))
        }
        return runCatching { engine.importConfig(confContent) }
            .mapCatching { refresh().getOrThrow() }
    }

    suspend fun exportConfig(): String = configManager.exportPlaintext()

    /** Tests connectivity to [remoteName]. Returns [Result.failure] when unreachable. */
    suspend fun testConnectivity(remoteName: String): Result<Unit> =
        engine.testConnectivity(remoteName)

    /**
     * Provides a live daemon for the duration of a daemon-mediated OAuth flow.
     * On success, persists the updated config; on failure, cleans up without persisting.
     */
    suspend fun <T> withDaemonForOAuth(block: suspend (app.lusk.virga.core.rclone.RcloneDaemon) -> T): T =
        engine.withDaemonForOAuth(block)

    /** Fetches storage quota for [remoteName]. Returns [Result.failure] when offline or unsupported. */
    suspend fun about(remoteName: String): Result<RemoteQuota> =
        runCatching { engine.about(remoteName) }

    /**
     * Returns the rclone provider schema (backend types + per-backend option list).
     * Never throws: returns an empty list when the daemon is unavailable or the call
     * fails — callers should fall back to freeform input in that case.
     */
    suspend fun providers(): List<RemoteProvider> = engine.providers()

    /**
     * Finds and removes duplicate files on [remoteName] using rclone dedupe.
     * [dedupeMode] controls which duplicate to keep ("skip" = safest).
     */
    suspend fun dedupe(remoteName: String, dedupeMode: String = "skip"): Result<Unit> =
        engine.dedupe(remoteName, dedupeMode)
}
