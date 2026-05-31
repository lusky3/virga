package app.lusk.virga.core.data

import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.rclone.RcloneEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository boundary for read-only remote file browsing. Keeps the low-level
 * [RcloneEngine] (and the daemon lifecycle) out of the UI layer: the explorer
 * ViewModel depends on this, not on the engine directly.
 */
@Singleton
class FileBrowserRepository @Inject constructor(
    private val engine: RcloneEngine,
) {
    /** Lists [path] within [remoteName]. Throws [app.lusk.virga.core.common.error.VirgaError] on failure. */
    suspend fun list(remoteName: String, path: String): List<FileItem> =
        engine.listDir("$remoteName:", path)

    /** Releases the shared rclone daemon when browsing no longer needs it. */
    suspend fun releaseDaemon() = engine.stopDaemon()
}
