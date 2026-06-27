package app.lusk.virga.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advisory pre-sync probe: sample-reads a few files from a SAF tree under a tight,
 * generous timeout. A timeout on a sample read is a strong "the card is failing" signal,
 * so the worker can fail fast with an actionable message instead of starting a doomed,
 * minutes-long run. Generous on purpose (few files, multi-second budget) to avoid false
 * positives on a merely-slow card.
 */
@Singleton
class SourceHealthCheck @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    enum class HealthResult { OK, TIMED_OUT, UNREADABLE }

    suspend fun probe(
        treeUri: String,
        sampleSize: Int = 3,
        perReadTimeoutMs: Long = 5_000L,
    ): HealthResult {
        if (!treeUri.startsWith("content://")) return HealthResult.OK
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: return HealthResult.UNREADABLE
        if (!tree.canRead()) return HealthResult.UNREADABLE
        val files = firstFiles(tree, sampleSize)
        for (f in files) {
            when (readProbe(f.uri, perReadTimeoutMs)) {
                HealthResult.TIMED_OUT -> return HealthResult.TIMED_OUT
                HealthResult.UNREADABLE -> return HealthResult.UNREADABLE
                HealthResult.OK -> Unit
            }
        }
        return HealthResult.OK
    }

    /** Depth-first first [limit] regular files in the tree. */
    private fun firstFiles(dir: DocumentFile, limit: Int): List<DocumentFile> {
        val out = mutableListOf<DocumentFile>()
        fun walk(d: DocumentFile) {
            for (child in d.listFiles()) {
                if (out.size >= limit) return
                if (child.isDirectory) walk(child) else out.add(child)
            }
        }
        walk(dir)
        return out
    }

    /** Reads a small head of one file under [timeoutMs]; closes the stream on timeout. */
    private suspend fun readProbe(uri: Uri, timeoutMs: Long): HealthResult = coroutineScope {
        val stream: InputStream = try {
            context.contentResolver.openInputStream(uri) ?: return@coroutineScope HealthResult.UNREADABLE
        } catch (e: Exception) {
            return@coroutineScope HealthResult.UNREADABLE
        }
        val job = launch(dispatchers.io) {
            stream.use { it.read(ByteArray(64 * 1024)) }
        }
        val done = withTimeoutOrNull(timeoutMs) { job.join() }
        if (done == null) {
            runCatching { stream.close() }
            job.cancel()
            HealthResult.TIMED_OUT
        } else if (job.isCancelled) {
            HealthResult.UNREADABLE
        } else {
            HealthResult.OK
        }
    }
}

/** Actionable message for a failed preflight, or null when healthy. */
internal fun preflightFailureMessage(result: SourceHealthCheck.HealthResult): String? =
    when (result) {
        SourceHealthCheck.HealthResult.TIMED_OUT ->
            "Your source card or drive didn't respond — it may be failing. Copy your files " +
                "off and replace it."
        SourceHealthCheck.HealthResult.UNREADABLE ->
            "Can't read the selected folder — re-select it for this task."
        SourceHealthCheck.HealthResult.OK -> null
    }
