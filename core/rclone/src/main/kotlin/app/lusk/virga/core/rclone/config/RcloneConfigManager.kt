package app.lusk.virga.core.rclone.config

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the rclone configuration at rest. The config (which holds OAuth tokens
 * and credentials) is stored encrypted via [EncryptedFile] backed by an Android
 * Keystore master key. It is only ever decrypted to [noBackupFilesDir] for the
 * lifetime of a daemon session, then deleted.
 */
@Singleton
class RcloneConfigManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val encryptedConf = File(context.filesDir, "rclone.conf.enc")
    private val plaintextConf = File(context.noBackupFilesDir, "rclone.conf")

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /** Decrypts the config to a temp file for the daemon. Creates an empty one if absent. */
    suspend fun decryptForDaemon(): File = withContext(dispatchers.io) {
        if (plaintextConf.exists()) plaintextConf.delete()
        if (!encryptedConf.exists()) {
            // First run: hand the daemon an empty, writable config.
            plaintextConf.writeText("")
            return@withContext plaintextConf
        }
        encryptedFile(encryptedConf).openFileInput().use { input ->
            plaintextConf.outputStream().use { output -> input.copyTo(output) }
        }
        plaintextConf
    }

    /** Re-encrypts the (possibly daemon-modified) plaintext config and removes the temp file. */
    suspend fun persistAndCleanup() = withContext(dispatchers.io) {
        if (plaintextConf.exists()) {
            val bytes = plaintextConf.readBytes()
            if (encryptedConf.exists()) encryptedConf.delete()
            encryptedFile(encryptedConf).openFileOutput().use { it.write(bytes) }
            plaintextConf.delete()
        }
    }

    /** Discards the temp plaintext config without persisting (e.g. on crash). */
    suspend fun cleanup() = withContext(dispatchers.io) {
        if (plaintextConf.exists()) plaintextConf.delete()
    }

    /** Imports an external rclone.conf, encrypting it into place. */
    suspend fun import(confContent: String) = withContext(dispatchers.io) {
        if (encryptedConf.exists()) encryptedConf.delete()
        encryptedFile(encryptedConf).openFileOutput().use { it.write(confContent.toByteArray()) }
    }

    /** Exports the decrypted config text (for the user to back up — with a warning in UI). */
    suspend fun exportPlaintext(): String = withContext(dispatchers.io) {
        if (!encryptedConf.exists()) return@withContext ""
        encryptedFile(encryptedConf).openFileInput().use { it.readBytes().decodeToString() }
    }

    fun hasConfig(): Boolean = encryptedConf.exists()

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
}
