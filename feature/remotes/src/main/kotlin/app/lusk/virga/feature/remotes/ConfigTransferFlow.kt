package app.lusk.virga.feature.remotes

import android.content.Context
import android.net.Uri
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.toUserMessage
import app.lusk.virga.core.data.RemoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Moves rclone.conf in and out of the app via the storage picker on behalf of
 * [RemotesViewModel]. Extracted as a collaborator so the VM stays under the
 * file-size budget; the VM's public API ([RemotesViewModel.importConfigFromUri],
 * [RemotesViewModel.exportConfigToUri]) delegates here unchanged.
 *
 * [transient] is the VM's transient UI state, shared (not copied) so outcome
 * messages surface through the same snackbar slice as every other flow.
 */
internal class ConfigTransferFlow(
    private val scope: CoroutineScope,
    private val context: Context,
    private val repository: RemoteRepository,
    private val dispatchers: DispatcherProvider,
    private val transient: MutableStateFlow<RemotesTransientState>,
) {

    private sealed interface ImportRead {
        data object CannotOpen : ImportRead
        data object TooLarge : ImportRead
        data class Ok(val text: String) : ImportRead
    }

    /** Imports an existing rclone.conf selected via the storage picker. */
    fun importFromUri(uri: Uri) {
        scope.launch {
            val read = withContext(dispatchers.io) {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext ImportRead.CannotOpen
                stream.use {
                    val bytes = it.readBytes()
                    if (bytes.size > MAX_IMPORT_BYTES) ImportRead.TooLarge
                    else ImportRead.Ok(bytes.toString(Charsets.UTF_8))
                }
            }
            when (read) {
                ImportRead.CannotOpen -> transient.value = transient.value.copy(
                    message = context.getString(R.string.remotes_msg_import_failed),
                )
                ImportRead.TooLarge -> transient.value = transient.value.copy(
                    message = context.getString(R.string.remotes_msg_import_too_large),
                )
                is ImportRead.Ok -> {
                    val result = repository.importConfig(read.text)
                    transient.value = transient.value.copy(
                        message = if (result.isSuccess) {
                            context.getString(R.string.remotes_msg_config_imported)
                        } else {
                            result.exceptionOrNull()?.toUserMessage()
                        },
                    )
                }
            }
        }
    }

    /**
     * Writes the decrypted rclone.conf to [uri] (a document created via the
     * storage picker). An empty config is reported without touching the file;
     * a null output stream or an [IOException] surfaces the failure message.
     * The config text contains credentials and tokens — it is NEVER logged.
     */
    fun exportToUri(uri: Uri) {
        scope.launch {
            val text = repository.exportConfig()
            if (text.isEmpty()) {
                transient.value = transient.value.copy(
                    message = context.getString(R.string.remotes_msg_export_nothing),
                )
                return@launch
            }
            val written = withContext(dispatchers.io) {
                try {
                    // "wt" truncates: SAF can hand back an existing document, and
                    // the default "w" mode would leave stale bytes past the end.
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: return@withContext false
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    true
                } catch (e: IOException) {
                    false
                }
            }
            transient.value = transient.value.copy(
                message = context.getString(
                    if (written) R.string.remotes_msg_config_exported
                    else R.string.remotes_msg_export_failed,
                ),
            )
        }
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 256 * 1024
    }
}
