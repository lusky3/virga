package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.SyncProgress
import app.lusk.virga.core.common.model.TransferProgress
import kotlinx.coroutines.flow.Flow

/**
 * High-level contract for driving rclone. Implementations manage an RC daemon
 * child process and translate these calls into authenticated RC API requests.
 *
 * Paths use rclone syntax: a remote path is "remoteName:some/dir"; a local path
 * is an absolute filesystem path (requires MANAGE_EXTERNAL_STORAGE for SD cards).
 */
interface RcloneEngine {
    suspend fun startDaemon(): RcloneDaemon
    suspend fun stopDaemon()
    suspend fun isDaemonHealthy(): Boolean

    suspend fun listRemotes(): List<Remote>
    suspend fun createRemote(name: String, type: String, params: Map<String, String>): Result<Unit>
    suspend fun deleteRemote(name: String): Result<Unit>
    suspend fun getConfig(): RcloneConfig
    suspend fun importConfig(confContent: String): Result<Unit>

    suspend fun listDir(remote: String, path: String): List<FileItem>

    /** Emits progress until the sync completes; the terminal emission has full counts. */
    fun sync(source: String, dest: String, options: SyncOptions): Flow<SyncProgress>
    fun bisync(path1: String, path2: String, options: BisyncOptions): Flow<SyncProgress>
    fun copyFile(source: String, dest: String): Flow<TransferProgress>
}
