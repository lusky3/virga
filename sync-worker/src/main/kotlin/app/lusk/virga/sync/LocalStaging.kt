package app.lusk.virga.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.lusk.virga.core.common.model.SyncDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the SAF (Storage Access Framework) tree URI world with rclone's
 * requirement for a real filesystem path.
 *
 * When [prepare] receives a `content://` source:
 * - UPLOAD: copies the SAF tree into a private cache dir so rclone can read it.
 * - DOWNLOAD: creates an empty cache dir; rclone writes there, then
 *   [writeBack] copies results into the SAF tree.
 *
 * A plain filesystem path passes through unchanged ([StagedSource.isStaged] = false).
 *
 * Bisync with a SAF source is rejected upstream (SyncWorker + SyncTaskEditViewModel)
 * and will never reach here under normal operation.
 */
@Singleton
class LocalStaging @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class StagedSource(
        val localPath: String,
        val isStaged: Boolean,
        val treeUriString: String? = null,
        val cacheDir: File? = null,
        /**
         * For a staged UPLOAD/BISYNC: whether the SAF tree was actually readable.
         * False means the persisted URI permission was lost — the worker MUST NOT
         * proceed (an empty staged dir mirror-synced upstream would delete the
         * cloud destination). Always true for DOWNLOAD / non-staged sources.
         */
        val sourceReadable: Boolean = true,
        /** Number of files copied into the staging dir (staged UPLOAD/BISYNC only). */
        val stagedFileCount: Int = 0,
    )

    /** Prepare the local path rclone should operate on. */
    suspend fun prepare(sourcePath: String, direction: SyncDirection): StagedSource =
        withContext(Dispatchers.IO) {
            if (!sourcePath.startsWith("content://")) {
                return@withContext StagedSource(localPath = sourcePath, isStaged = false)
            }
            val hash = sourcePath.hashCode().toUInt().toString(16)
            val stageDir = File(context.cacheDir, "saf-stage/$hash")
            stageDir.deleteRecursively()
            stageDir.mkdirs()

            if (direction == SyncDirection.UPLOAD || direction == SyncDirection.BISYNC) {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(sourcePath))
                // canRead() reflects whether we still hold the persisted permission.
                // If the tree is gone/unreadable, signal it so the worker fails
                // rather than treating an empty stage as "source has no files".
                if (tree == null || !tree.canRead()) {
                    return@withContext StagedSource(
                        localPath = stageDir.absolutePath,
                        isStaged = true,
                        treeUriString = sourcePath,
                        cacheDir = stageDir,
                        sourceReadable = false,
                        stagedFileCount = 0,
                    )
                }
                val count = copyTreeToLocal(tree, stageDir)
                return@withContext StagedSource(
                    localPath = stageDir.absolutePath,
                    isStaged = true,
                    treeUriString = sourcePath,
                    cacheDir = stageDir,
                    sourceReadable = true,
                    stagedFileCount = count,
                )
            }
            // DOWNLOAD: leave stageDir empty; rclone fills it, writeBack copies out.
            StagedSource(
                localPath = stageDir.absolutePath,
                isStaged = true,
                treeUriString = sourcePath,
                cacheDir = stageDir,
            )
        }

    /**
     * Copy rclone's output (in [staged.cacheDir]) back into the SAF tree.
     * Only meaningful for staged downloads. Conservative: creates/overwrites files
     * only — does not delete SAF entries absent from the cache.
     */
    suspend fun writeBack(staged: StagedSource): Unit = withContext(Dispatchers.IO) {
        val cacheDir = staged.cacheDir ?: return@withContext
        val treeUri = staged.treeUriString ?: return@withContext
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext
        copyLocalToTree(cacheDir, tree)
    }

    /** Delete the staging cache dir. Errors are silently ignored. */
    suspend fun cleanup(staged: StagedSource): Unit = withContext(Dispatchers.IO) {
        runCatching { staged.cacheDir?.deleteRecursively() }
    }

    // --- private helpers ---

    /** Copies [dir] into [dest] recursively; returns the number of files written. */
    private fun copyTreeToLocal(dir: DocumentFile, dest: File): Int {
        var count = 0
        for (child in dir.listFiles()) {
            val target = safeChild(dest, child.name) ?: continue
            if (child.isDirectory) {
                target.mkdirs()
                count += copyTreeToLocal(child, target)
            } else {
                copyDocumentToFile(child.uri, target)
                count++
            }
        }
        return count
    }

    /**
     * Resolve [name] as a direct child of [dest], rejecting provider-supplied
     * names that could escape the staging dir — null/empty, path separators,
     * '.'/'..', or any name whose canonical path lands outside [dest]. A
     * malicious or buggy DocumentsProvider can return arbitrary display names,
     * so this is the boundary that keeps staged copies app-private.
     */
    private fun safeChild(dest: File, name: String?): File? {
        if (name.isNullOrEmpty() || name == "." || name == ".." ||
            name.contains('/') || name.contains('\\')
        ) {
            return null
        }
        val child = File(dest, name)
        val destPrefix = dest.canonicalPath + File.separator
        return if (child.canonicalPath.startsWith(destPrefix)) child else null
    }

    private fun copyDocumentToFile(uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun copyLocalToTree(src: File, treeDir: DocumentFile) {
        val children = src.listFiles() ?: return
        // Snapshot the tree's existing entries once. DocumentFile.findFile is an
        // O(n) provider query per call, so calling it per child would be O(n^2)
        // round-trips for a wide directory.
        val existing = treeDir.listFiles().mapNotNull { doc -> doc.name?.let { it to doc } }.toMap()
        for (child in children) {
            if (child.isDirectory) {
                val subDoc = existing[child.name]?.takeIf { it.isDirectory }
                    ?: treeDir.createDirectory(child.name) ?: continue
                copyLocalToTree(child, subDoc)
            } else {
                val mime = context.contentResolver.getType(Uri.fromFile(child)) ?: "application/octet-stream"
                val docFile = existing[child.name]
                    ?: treeDir.createFile(mime, child.name) ?: continue
                context.contentResolver.openOutputStream(docFile.uri, "wt")?.use { output ->
                    child.inputStream().use { input -> input.copyTo(output) }
                }
            }
        }
    }
}
