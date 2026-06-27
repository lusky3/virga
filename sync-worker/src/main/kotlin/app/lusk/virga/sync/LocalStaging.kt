package app.lusk.virga.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.model.SyncDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
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
    private val dispatchers: DispatcherProvider,
) {

    enum class CopyOutcome { COPIED, ERROR, TIMEOUT }

    private companion object {
        /** Max wall-clock a single staged file read may take before it's abandoned.
         *  Bounds a wedged read on a failing card so staging can't hang forever. */
        const val PER_FILE_READ_TIMEOUT_MS = 30_000L
    }

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
        /**
         * For a staged UPLOAD/BISYNC: whether EVERY source file was copied into the
         * staging dir. False means at least one file couldn't be read (null stream,
         * IO error, or an unsafe provider-supplied name). A delete-enabled mirror
         * MUST NOT run against an incomplete stage — rclone would delete the missing
         * files' counterparts on the cloud destination. Always true for DOWNLOAD /
         * non-staged sources.
         */
        val fullyStaged: Boolean = true,
        /** Number of source files abandoned because a single read exceeded the per-file
         *  timeout (a strong "the card is failing" signal). Counted within [errors]-style
         *  accounting too: a timed-out file is NOT in the staged copy. */
        val readTimeouts: Int = 0,
    )

    /**
     * Prepare the local path rclone should operate on. [runId] suffixes the stage
     * dir so concurrent runs of the same task (or two tasks sharing a SAF source)
     * never collide — without it both keyed only on the source hash and the
     * leading deleteRecursively() would wipe each other's in-flight stage.
     */
    suspend fun prepare(sourcePath: String, direction: SyncDirection, runId: Long): StagedSource =
        withContext(Dispatchers.IO) {
            if (!sourcePath.startsWith("content://")) {
                return@withContext StagedSource(localPath = sourcePath, isStaged = false)
            }
            val hash = sourcePath.hashCode().toUInt().toString(16)
            val stageDir = File(context.cacheDir, "saf-stage/$hash-$runId")
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
                val tally = CopyTally()
                copyTreeToLocal(tree, stageDir, tally)
                return@withContext StagedSource(
                    localPath = stageDir.absolutePath,
                    isStaged = true,
                    treeUriString = sourcePath,
                    cacheDir = stageDir,
                    sourceReadable = true,
                    stagedFileCount = tally.copied,
                    fullyStaged = tally.errors == 0,
                    readTimeouts = tally.timeouts,
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
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: throw IOException("Can't open the destination folder — re-select it for this task.")
        val tally = CopyTally()
        copyLocalToTree(cacheDir, tree, tally)
        // Fail loudly: silently dropping downloaded files would record a clean sync
        // while the user's folder is missing data (the cache dir is about to be wiped).
        if (tally.errors > 0) {
            throw IOException("Couldn't write ${tally.errors} downloaded file(s) to the folder.")
        }
    }

    /** Delete the staging cache dir. Errors are silently ignored. */
    suspend fun cleanup(staged: StagedSource): Unit = withContext(Dispatchers.IO) {
        runCatching { staged.cacheDir?.deleteRecursively() }
    }

    // --- private helpers ---

    /** Running totals for a staging copy: files written vs. files that failed/skipped. */
    private class CopyTally(var copied: Int = 0, var errors: Int = 0, var timeouts: Int = 0)

    /**
     * Copies [dir] into [dest] recursively, accumulating into [tally]. Any file that
     * can't be staged — an unsafe provider name, a null input stream, or an IO error —
     * is counted as an error rather than silently dropped, so the caller can refuse to
     * run a delete-enabled mirror against an incomplete stage.
     */
    private suspend fun copyTreeToLocal(dir: DocumentFile, dest: File, tally: CopyTally) {
        for (child in dir.listFiles()) {
            val target = safeChild(dest, child.name)
            if (target == null) {
                // A name we can't safely stage still corresponds to a real source file;
                // count it so a mirror upload doesn't delete its remote counterpart.
                tally.errors++
                continue
            }
            if (child.isDirectory) {
                target.mkdirs()
                copyTreeToLocal(child, target, tally)
            } else {
                when (copyDocumentToFileTimed(child.uri, target)) {
                    CopyOutcome.COPIED -> tally.copied++
                    CopyOutcome.TIMEOUT -> { tally.timeouts++; tally.errors++ }
                    CopyOutcome.ERROR -> tally.errors++
                }
            }
        }
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

    /** Copies one SAF document to [dest] with a per-file read timeout. On timeout the
     *  stream is closed from this (outer) coroutine to unblock a wedged read where the
     *  provider honors close(); returns [CopyOutcome.TIMEOUT]. Open/IO failures →
     *  [CopyOutcome.ERROR]; success → [CopyOutcome.COPIED]. */
    private suspend fun copyDocumentToFileTimed(uri: Uri, dest: File): CopyOutcome {
        val stream = try {
            context.contentResolver.openInputStream(uri) ?: return CopyOutcome.ERROR
        } catch (e: Exception) {
            return CopyOutcome.ERROR
        }
        return copyStreamTimed(stream, dest, PER_FILE_READ_TIMEOUT_MS)
    }

    /** Shared copy-with-deadline logic. Runs the blocking copy on [dispatchers.io]; on
     *  timeout, closes the stream (from this coroutine, a different thread) to break a
     *  wedged read, then counts a TIMEOUT. */
    private suspend fun copyStreamTimed(stream: InputStream, dest: File, timeoutMs: Long): CopyOutcome =
        coroutineScope {
            val copy = launch(dispatchers.io) {
                stream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            }
            val finished = withTimeoutOrNull(timeoutMs) { copy.join() }
            if (finished == null) {
                runCatching { stream.close() } // unblock the wedged read where honored
                copy.cancel()
                CopyOutcome.TIMEOUT
            } else if (copy.isCancelled) {
                CopyOutcome.ERROR
            } else {
                CopyOutcome.COPIED
            }
        }

    /** Test-only entry point: run the timeout/close logic against a supplied stream. */
    internal suspend fun copyDocumentToFileTimedForTest(
        stream: InputStream,
        dest: File,
        timeoutMs: Long,
    ): CopyOutcome = copyStreamTimed(stream, dest, timeoutMs)

    private fun copyLocalToTree(src: File, treeDir: DocumentFile, tally: CopyTally) {
        val children = src.listFiles() ?: return
        // Snapshot the tree's existing entries once. DocumentFile.findFile is an
        // O(n) provider query per call, so calling it per child would be O(n^2)
        // round-trips for a wide directory.
        val existing = treeDir.listFiles().mapNotNull { doc -> doc.name?.let { it to doc } }.toMap()
        for (child in children) {
            if (child.isDirectory) {
                val subDoc = existing[child.name]?.takeIf { it.isDirectory }
                    ?: treeDir.createDirectory(child.name)
                if (subDoc == null) { tally.errors++; continue }
                copyLocalToTree(child, subDoc, tally)
            } else {
                val mime = context.contentResolver.getType(Uri.fromFile(child)) ?: "application/octet-stream"
                val docFile = existing[child.name] ?: treeDir.createFile(mime, child.name)
                if (docFile == null) { tally.errors++; continue }
                val ok = runCatching {
                    val out = context.contentResolver.openOutputStream(docFile.uri, "wt") ?: return@runCatching false
                    out.use { output -> child.inputStream().use { input -> input.copyTo(output) } }
                    true
                }.getOrDefault(false)
                if (ok) tally.copied++ else tally.errors++
            }
        }
    }
}
