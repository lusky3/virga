package app.lusk.virga.feature.sync

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Best-effort resolution of a SAF tree [uri] to a real filesystem path, for the
 * all-files-access (FOSS) build where rclone can read the path directly. Returns
 * null when the volume can't be mapped or the resolved path doesn't exist.
 *
 * Shared by [SyncTaskEditScreen] and the first-sync wizard — kept in its own file
 * so the dependency is explicit rather than relying on package-private co-location.
 *
 * Rejects path traversal: a malicious DocumentsProvider could return a document id
 * with ".." segments, so the canonical result must stay within the storage root.
 */
internal fun resolveTreeUriToPath(uri: Uri): String? {
    val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
    val colonIdx = docId.indexOf(':')
    if (colonIdx < 0) return null
    val volume = docId.substring(0, colonIdx)
    val relativePath = docId.substring(colonIdx + 1)
    val base = if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory().absolutePath
    } else {
        "/storage/$volume"
    }
    val resolved = if (relativePath.isEmpty()) File(base) else File(base, relativePath)
    val canonical = runCatching { resolved.canonicalPath }.getOrNull() ?: return null
    val canonicalBase = runCatching { File(base).canonicalPath }.getOrNull() ?: return null
    if (canonical != canonicalBase && !canonical.startsWith("$canonicalBase/")) return null
    return if (resolved.exists()) canonical else null
}
