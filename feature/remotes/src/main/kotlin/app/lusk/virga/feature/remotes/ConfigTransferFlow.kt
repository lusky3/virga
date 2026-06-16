package app.lusk.virga.feature.remotes

import android.content.Context
import android.net.Uri
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import app.lusk.virga.core.common.error.toUserMessage
import app.lusk.virga.core.data.RemoteRepository
import app.lusk.virga.core.rclone.crypto.BadPassphraseException
import app.lusk.virga.core.rclone.crypto.ConfigCrypto
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

    private sealed interface ImportBytes {
        data object CannotOpen : ImportBytes
        data object TooLarge : ImportBytes
        data class Ok(val bytes: ByteArray) : ImportBytes
    }

    /**
     * Imports a config file selected via the storage picker.
     *
     * If [passphrase] is null and the file is an encrypted container, this method
     * signals the UI to prompt for a passphrase by setting
     * [RemotesTransientState.pendingEncryptedImport]. When [passphrase] is supplied the
     * container is decrypted; a wrong passphrase surfaces a snackbar and leaves the
     * prompt open so the user can retry. A non-encrypted file is imported as
     * plain UTF-8.
     *
     * When [mergeMode] is true the incoming sections are merged into the current config
     * via [RemoteRepository.importConfigMerged]; when false the config is replaced
     * outright via [RemoteRepository.importConfig].
     *
     * The caller is responsible for zeroing [passphrase] after this returns.
     */
    fun importFromUri(uri: Uri, passphrase: CharArray? = null, mergeMode: Boolean = false) {
        scope.launch {
            val read = withContext(dispatchers.io) {
                val stream = context.contentResolver.openInputStream(uri)
                if (stream == null) {
                    ImportBytes.CannotOpen
                } else {
                    stream.use {
                        val bytes = it.readBytes()
                        if (bytes.size > MAX_IMPORT_BYTES) ImportBytes.TooLarge
                        else ImportBytes.Ok(bytes)
                    }
                }
            }
            when (read) {
                ImportBytes.CannotOpen -> transient.value = transient.value.copy(
                    message = context.getString(R.string.remotes_msg_import_failed),
                )
                ImportBytes.TooLarge -> transient.value = transient.value.copy(
                    message = context.getString(R.string.remotes_msg_import_too_large),
                )
                is ImportBytes.Ok -> handleImportBytes(uri, read.bytes, passphrase, mergeMode)
            }
        }
    }

    private suspend fun handleImportBytes(
        uri: Uri,
        bytes: ByteArray,
        passphrase: CharArray?,
        mergeMode: Boolean,
    ) {
        if (ConfigCrypto.isEncryptedContainer(bytes)) {
            if (passphrase == null) {
                // Signal UI to prompt for passphrase; do not import yet.
                transient.value = transient.value.copy(pendingEncryptedImport = uri)
                return
            }
            val text = try {
                withContext(dispatchers.io) {
                    ConfigCrypto.decrypt(bytes, passphrase)
                }
            } catch (e: BadPassphraseException) {
                // Keep pendingEncryptedImport set so the dialog stays open for retry.
                transient.value = transient.value.copy(
                    pendingEncryptedImport = uri,
                    message = context.getString(R.string.remotes_msg_import_wrong_passphrase),
                )
                return
            } finally {
                passphrase.fill(' ')
            }
            finishImport(text, clearPassphrasePrompt = true, mergeMode = false)
        } else {
            // Plain UTF-8 — merge or replace based on caller's choice.
            finishImport(bytes.toString(Charsets.UTF_8), clearPassphrasePrompt = true, mergeMode = mergeMode)
        }
    }

    private suspend fun finishImport(text: String, clearPassphrasePrompt: Boolean, mergeMode: Boolean) {
        val result = if (mergeMode) repository.importConfigMerged(text) else repository.importConfig(text)
        val successMsg = if (mergeMode) {
            context.getString(R.string.remotes_msg_config_imported_merged)
        } else {
            context.getString(R.string.remotes_msg_config_imported)
        }
        transient.value = transient.value.copy(
            pendingEncryptedImport = if (clearPassphrasePrompt) null else transient.value.pendingEncryptedImport,
            message = if (result.isSuccess) successMsg else result.exceptionOrNull()?.toUserMessage(),
        )
    }

    /**
     * Writes the rclone.conf to [uri] (a document created via the storage picker).
     *
     * When [redacted]=true the config is written with sensitive values replaced by
     * placeholders (ignores [passphrase], zeroes it). When [passphrase] is non-null
     * and [redacted]=false the config is encrypted via [ConfigCrypto.encrypt]. When
     * both are absent the raw plaintext path is used.
     *
     * An empty config is reported without touching the file; a null output stream or
     * an [IOException] surfaces the failure message. Config text / derived key bytes
     * are NEVER logged.
     */
    fun exportToUri(uri: Uri, passphrase: CharArray? = null, redacted: Boolean = false) {
        scope.launch {
            val text = if (redacted) {
                passphrase?.fill(' ')
                repository.exportConfigRedacted()
            } else {
                repository.exportConfig()
            }
            if (text.isEmpty()) {
                if (!redacted) passphrase?.fill(' ')
                transient.value = transient.value.copy(
                    message = context.getString(R.string.remotes_msg_export_nothing),
                )
                return@launch
            }
            val encryptWith: CharArray? = if (redacted) null else passphrase
            val written = withContext(dispatchers.io) {
                try {
                    // "wt" truncates: SAF can hand back an existing document, and
                    // the default "w" mode would leave stale bytes past the end.
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: return@withContext false
                    val payload = if (encryptWith != null) {
                        try {
                            ConfigCrypto.encrypt(text, encryptWith)
                        } finally {
                            encryptWith.fill(' ')
                        }
                    } else {
                        text.toByteArray(Charsets.UTF_8)
                    }
                    stream.use { it.write(payload) }
                    true
                } catch (e: IOException) {
                    false
                }
            }
            val msgRes = when {
                !written -> R.string.remotes_msg_export_failed
                encryptWith != null -> R.string.remotes_msg_config_exported_encrypted
                else -> R.string.remotes_msg_config_exported
            }
            transient.value = transient.value.copy(
                message = context.getString(msgRes),
            )
        }
    }

    /**
     * Exports a single remote's section to [uri].
     *
     * When [redacted]=true sensitive values are masked (ignores [passphrase]).
     * When [passphrase] is non-null and [redacted]=false the section text is encrypted.
     * When both are absent the raw section plaintext is written.
     */
    fun exportSectionToUri(
        remoteName: String,
        uri: Uri,
        passphrase: CharArray? = null,
        redacted: Boolean = false,
    ) {
        scope.launch {
            val text = if (redacted) {
                passphrase?.fill(' ')
                repository.exportConfigSectionRedacted(remoteName)
            } else {
                repository.exportConfigSection(remoteName)
            }
            if (text.isBlank()) {
                if (!redacted) passphrase?.fill(' ')
                transient.value = transient.value.copy(
                    message = context.getString(R.string.remotes_msg_export_nothing),
                )
                return@launch
            }
            val encryptSectionWith: CharArray? = if (redacted) null else passphrase
            val written = withContext(dispatchers.io) {
                try {
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: return@withContext false
                    val payload = if (encryptSectionWith != null) {
                        try {
                            ConfigCrypto.encrypt(text, encryptSectionWith)
                        } finally {
                            encryptSectionWith.fill(' ')
                        }
                    } else {
                        text.toByteArray(Charsets.UTF_8)
                    }
                    stream.use { it.write(payload) }
                    true
                } catch (e: IOException) {
                    false
                }
            }
            val msg = if (written) {
                context.getString(R.string.remotes_msg_section_exported, remoteName)
            } else {
                context.getString(R.string.remotes_msg_export_failed)
            }
            transient.value = transient.value.copy(message = msg)
        }
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 256 * 1024
    }
}
