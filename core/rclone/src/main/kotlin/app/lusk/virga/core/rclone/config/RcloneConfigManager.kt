package app.lusk.virga.core.rclone.config

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // Stored in noBackupFilesDir so the encrypted config (OAuth tokens) is never
    // eligible for cloud backup / device transfer, independent of XML backup rules.
    private val encryptedConf = File(context.noBackupFilesDir, "rclone.conf.enc").also { dest ->
        // One-time migration: earlier builds stored this in the backup-eligible filesDir.
        // The EncryptedFile master key is Keystore-bound (not path-bound), so moving the
        // ciphertext keeps it decryptable.
        val legacy = File(context.filesDir, "rclone.conf.enc")
        if (legacy.exists() && !dest.exists()) {
            runCatching { legacy.copyTo(dest, overwrite = true); legacy.delete() }
        }
    }
    private val plaintextConf = File(context.noBackupFilesDir, "rclone.conf")

    // Serializes every mutation of the shared rclone.conf / rclone.conf.enc files.
    // decrypt/persist/cleanup/import all read-modify-write the same two paths; an
    // interleaving (e.g. a concurrent createRemote and stopDaemon) could otherwise
    // delete-then-write the ciphertext out from under each other and lose or
    // corrupt the encrypted credential store.
    private val ioLock = Mutex()

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /** Decrypts the config to a temp file for the daemon. Creates an empty one if absent. */
    suspend fun decryptForDaemon(): File = withContext(dispatchers.io) {
        ioLock.withLock {
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
    }

    /** Re-encrypts the (possibly daemon-modified) plaintext config and removes the temp file. */
    suspend fun persistAndCleanup() = withContext(dispatchers.io) {
        ioLock.withLock {
            if (plaintextConf.exists()) {
                val bytes = plaintextConf.readBytes()
                if (encryptedConf.exists()) encryptedConf.delete()
                encryptedFile(encryptedConf).openFileOutput().use { it.write(bytes) }
                plaintextConf.delete()
            }
        }
    }

    /** Discards the temp plaintext config without persisting (e.g. on crash). */
    suspend fun cleanup() = withContext(dispatchers.io) {
        ioLock.withLock {
            if (plaintextConf.exists()) plaintextConf.delete()
        }
    }

    /** Imports an external rclone.conf, encrypting it into place. */
    suspend fun import(confContent: String) = withContext(dispatchers.io) {
        ioLock.withLock {
            if (encryptedConf.exists()) encryptedConf.delete()
            encryptedFile(encryptedConf).openFileOutput().use { it.write(confContent.toByteArray()) }
        }
    }

    /** Exports the decrypted config text (for the user to back up — with a warning in UI). */
    suspend fun exportPlaintext(): String = withContext(dispatchers.io) {
        ioLock.withLock {
            if (!encryptedConf.exists()) return@withContext ""
            encryptedFile(encryptedConf).openFileInput().use { it.readBytes().decodeToString() }
        }
    }

    suspend fun hasConfig(): Boolean = withContext(dispatchers.io) { encryptedConf.exists() }

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
}
