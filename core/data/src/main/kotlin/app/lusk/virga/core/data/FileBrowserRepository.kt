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
     * Creates directory [path] within [remoteName] (idempotent). Throws
     * [app.lusk.virga.core.common.error.VirgaError] on failure.
     */
    suspend fun mkdir(remoteName: String, path: String) =
        engine.mkdir("$remoteName:", path)

    /** Deletes a single file at [path] within [remoteName]. Throws [app.lusk.virga.core.common.error.VirgaError] on failure. */
    suspend fun deleteFile(remoteName: String, path: String) =
        engine.deleteFile("$remoteName:", path)

    /**
     * Moves/renames [fromPath] to [toPath] within [remoteName] via
     * `operations/movefile`. Use for rename (same dir, new name) or move
     * (same remote, different dir). Throws [VirgaError] on failure.
     */
    suspend fun moveFile(remoteName: String, fromPath: String, toPath: String) =
        engine.moveFile("$remoteName:$fromPath", "$remoteName:$toPath")

    /**
     * Copies [fromPath] to [toPath] within [remoteName]. Throws [VirgaError] on failure.
     */
    suspend fun copyFile(remoteName: String, fromPath: String, toPath: String) =
        engine.copyFile("$remoteName:$fromPath", "$remoteName:$toPath")

    /**
     * Recursively deletes directory [path] within [remoteName] via `operations/purge`.
     * Throws [VirgaError] on failure.
     */
    suspend fun purge(remoteName: String, path: String) =
        engine.purge("$remoteName:", path)

    /**
     * Downloads [remotePath] from [remoteName] into [cacheDir]/[name] and returns the staged [File].
     * Caller must pass the absolute path to the shared cache subdirectory (e.g. File(context.cacheDir, "shared")).
     * Throws [VirgaError] on failure.
     */
    suspend fun downloadToCache(
        remoteName: String,
        remotePath: String,
        cacheDir: java.io.File,
    ): java.io.File {
        cacheDir.mkdirs()
        val rawName = remotePath.substringAfterLast('/').substringAfterLast('\\')
        val name = rawName.replace("..", "_").trim().ifBlank { "download" }
        engine.downloadFile(remoteName, remotePath, cacheDir.absolutePath, name)
        return java.io.File(cacheDir, name)
    }

    /**
     * Uploads [localFile] to [remotePath] within [remoteName].
     * [localFile] must be a plain filesystem file (not a content:// URI) — rclone cannot read content URIs.
     * Throws [VirgaError] on failure.
     */
    suspend fun uploadFromLocal(
        localFile: java.io.File,
        remoteName: String,
        remotePath: String,
    ) {
        // Resolve against the absolute path so a relative input (parent == null, e.g.
        // File("notes.txt")) still yields a real directory as srcDir — passing the file's
        // own path there would break operations/copyfile source resolution.
        val absolute = localFile.absoluteFile
        val srcDir = absolute.parent
            ?: throw IllegalArgumentException("localFile must resolve to a parent directory: $localFile")
        engine.uploadFile(
            srcDir = srcDir,
            srcName = absolute.name,
            remoteName = remoteName,
            remotePath = remotePath,
        )
    }

    /**
     * Releases the daemon when browsing closes — best-effort: stops it only if no
     * sync is currently leasing it (the browser is a non-leasing consumer using the
     * warm daemon), so closing the browser can't kill an in-flight sync.
     */
    suspend fun releaseDaemon() = engine.stopDaemonIfIdle()
}
