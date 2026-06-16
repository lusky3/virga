package app.lusk.virga.feature.explorer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Holds the SAF launchers and intent helpers needed for file transfer actions.
 *
 * Separating these from [FileBrowserScreen] keeps both files under the 500-line limit
 * and concentrates all Intent/FileProvider/SAF plumbing in one place.
 */
internal class TransferLaunchers(
    val createDocument: ManagedActivityResultLauncher<String, Uri?>,
    val openDocument: ManagedActivityResultLauncher<Array<String>, Uri?>,
)

/**
 * Remembers [TransferLaunchers] wired to the given callbacks.
 *
 * @param onSaveUri called with the SAF [Uri] the user chose for "Save to device".
 * @param onPickUri called with the SAF [Uri] the user chose for upload.
 */
@Composable
internal fun rememberTransferLaunchers(
    onSaveUri: (Uri) -> Unit,
    onPickUri: (Uri) -> Unit,
): TransferLaunchers {
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) onSaveUri(uri)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPickUri(uri)
    }
    return remember(createDocument, openDocument) { TransferLaunchers(createDocument, openDocument) }
}

/** Builds a FileProvider content:// [Uri] for [file]. */
internal fun fileProviderUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/** Starts ACTION_VIEW for [file] with a FileProvider URI + read grant. */
internal fun openFile(context: Context, file: File, mimeType: String?) {
    val uri = fileProviderUri(context, file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // No installed app may handle ACTION_VIEW for this type (binary blobs, odd extensions);
    // startActivity would otherwise crash the process. shareFile needs no guard because
    // createChooser handles the empty-handler case itself.
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.explorer_no_app_to_open, Toast.LENGTH_SHORT).show()
    }
}

/** Fires ACTION_SEND chooser for [file] with a FileProvider URI + read grant. */
internal fun shareFile(context: Context, file: File, mimeType: String?) {
    val uri = fileProviderUri(context, file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/**
 * Copies [srcFile] bytes into [destUri] (a SAF content:// uri) via [Context.contentResolver].
 * Must be called off the main thread.
 */
internal fun copyToSafUri(context: Context, srcFile: File, destUri: Uri) {
    // Open the input inside its own use{} first: if openOutputStream returns null and
    // error() throws, the input stream is still closed (it never leaks half-opened).
    srcFile.inputStream().use { input ->
        val output: OutputStream = context.contentResolver.openOutputStream(destUri)
            ?: error("Cannot open output stream for $destUri")
        output.use { o -> input.copyTo(o) }
    }
}

/**
 * Copies bytes from a SAF content:// [srcUri] into a local [destFile].
 * Must be called off the main thread.
 */
internal fun copyFromSafUri(context: Context, srcUri: Uri, destFile: File) {
    val input: InputStream = context.contentResolver.openInputStream(srcUri)
        ?: error("Cannot open input stream for $srcUri")
    input.use { i -> destFile.outputStream().use { o -> i.copyTo(o) } }
}

/** Returns the raw display name for a content:// URI. Use [sanitizeSafName] before using as a filename. */
internal fun safDisplayName(context: Context, uri: Uri): String {
    val cols = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    val cursor = context.contentResolver.query(uri, cols, null, null, null)
    return cursor?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "upload"
}

/**
 * Reduces a SAF or remote-supplied name to a safe single path segment.
 * Strips any directory separators, collapses ".." components, and falls
 * back to [fallback] for blank/empty results — preventing path traversal
 * when the name is used to construct a local cache [File] path.
 */
internal fun sanitizeSafName(rawName: String, fallback: String = "upload"): String {
    val stripped = rawName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace("..", "_")
        .trim()
    return stripped.ifBlank { fallback }
}
