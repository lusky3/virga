package app.lusk.virga.share

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * URI-to-local-file helpers for the share receiver.
 *
 * Mirrors the logic in `feature:explorer`'s internal `copyFromSafUri` /
 * `sanitizeSafName` — duplicated here because `feature:explorer` is
 * `internal` to that module and `app` must not depend on a feature module
 * for a lightweight copy helper.
 */

/**
 * Reduces a SAF or remote-supplied name to a safe single path segment.
 * Strips directory separators and ".." components; falls back to [fallback]
 * for blank results — preventing path traversal when the name is used to
 * construct a local cache [File] path.
 */
internal fun sanitizeSafName(rawName: String, fallback: String = "upload"): String {
    val stripped = rawName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace("..", "_")
        .replace("\u0000", "") // strip a NUL byte; it would otherwise throw at File() construction
        .trim()
    return stripped.ifBlank { fallback }
}

/** Returns the display name for a content:// [Uri], or a fallback. */
internal fun safDisplayName(context: Context, uri: Uri): String {
    val cols = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    val cursor = context.contentResolver.query(uri, cols, null, null, null)
    return cursor?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "upload"
}

/**
 * Copies bytes from a SAF content:// [srcUri] into [destFile].
 * Must be called off the main thread.
 *
 * @throws IOException if the content resolver cannot open the URI or writing fails.
 * @throws SecurityException if the calling context lacks URI permission.
 */
@Throws(IOException::class, SecurityException::class)
internal fun copyFromSafUri(context: Context, srcUri: Uri, destFile: File) {
    val input = context.contentResolver.openInputStream(srcUri)
        ?: throw IOException("Cannot open input stream for $srcUri")
    input.use { i -> destFile.outputStream().use { o -> i.copyTo(o) } }
}

/**
 * Stages [uri] into [stagingDir] with a sanitized filename.
 * Returns the [File] written, or null if the URI could not be read.
 *
 * Does NOT rethrow [CancellationException] — callers must handle cancellation
 * at the coroutine boundary; this function is called from within a per-item
 * try/catch that accumulates failures.
 */
internal fun stageUri(context: Context, uri: Uri, stagingDir: File): File? =
    try {
        val rawName = safDisplayName(context, uri)
        val safeName = sanitizeSafName(rawName)
        val dest = File(stagingDir, safeName)
        copyFromSafUri(context, uri, dest)
        dest
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
