package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteQuota
import app.lusk.virga.core.common.model.SyncProgress
import kotlinx.coroutines.flow.Flow

/**
 * High-level contract for driving rclone. Implementations manage an RC daemon
 * child process and translate these calls into authenticated RC API requests.
 *
 * Paths use rclone syntax: a remote path is "remoteName:some/dir"; a local path
 * is an absolute filesystem path (requires MANAGE_EXTERNAL_STORAGE for SD cards).
 *
 * ## Failure convention
 * Every operation signals failure the same way: `suspend` methods **throw**
 * [app.lusk.virga.core.common.error.VirgaError] on failure (rather than some
 * returning `Result` and others throwing), and the streaming [sync]/[bisync]
 * flows surface failures by terminating the flow with a `VirgaError`. Callers
 * that need a `Result` (e.g. repositories at the UI boundary) wrap the call in
 * `runCatching`.
 */
interface RcloneEngine {
    suspend fun startDaemon(): RcloneDaemon
    suspend fun stopDaemon()
    suspend fun isDaemonHealthy(): Boolean

    suspend fun listRemotes(): List<Remote>
    suspend fun createRemote(name: String, type: String, params: Map<String, String>)
    suspend fun deleteRemote(name: String)
    suspend fun getConfig(): RcloneConfig
    suspend fun importConfig(confContent: String)

    suspend fun listDir(
        remote: String,
        path: String,
        recurse: Boolean = false,
        filters: List<String> = emptyList(),
    ): List<FileItem>

    /** Deletes a single file at [remote]:[path]. Throws [VirgaError] on failure. */
    suspend fun deleteFile(remote: String, path: String)

    /** Moves/renames a file. Paths use rclone "remote:path" syntax. Throws [VirgaError] on failure. */
    suspend fun moveFile(source: String, dest: String)

    /**
     * Fetches storage quota for [remoteName] via `operations/about`.
     * Throws [VirgaError] on failure; any field in the result may be null
     * when the backend does not report it.
     */
    suspend fun about(remoteName: String): RemoteQuota

    /** Emits progress until the sync completes; the terminal emission has full counts. */
    fun sync(source: String, dest: String, options: SyncOptions): Flow<SyncProgress>
    fun bisync(path1: String, path2: String, options: BisyncOptions): Flow<SyncProgress>
}
