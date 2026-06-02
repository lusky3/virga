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

    /**
     * Releases the daemon when browsing closes — best-effort: stops it only if no
     * sync is currently leasing it (the browser is a non-leasing consumer using the
     * warm daemon), so closing the browser can't kill an in-flight sync.
     */
    suspend fun releaseDaemon() = engine.stopDaemonIfIdle()
}
