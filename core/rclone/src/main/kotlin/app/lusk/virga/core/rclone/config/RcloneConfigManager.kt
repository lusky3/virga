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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
            // Drop a stale recovery probe left by a process kill mid-recovery (it's
            // ciphertext, not plaintext, but there's no reason to keep a second copy).
            runCatching { File(encryptedConf.parentFile, "${encryptedConf.name}.tmp").delete() }
            if (!encryptedConf.exists()) {
                // First run: hand the daemon an empty, writable config.
                plaintextConf.writeText("")
                restrictToOwner(plaintextConf)
                return@withContext plaintextConf
            }
            // Decrypt under the file's own name (its AEAD associated data). If that
            // fails, attempt the one-time recovery for configs written by the old
            // buggy temp-rename (keyed to the "*.enc.tmp" name) and re-persist them
            // correctly so the heal happens once.
            val plain = runCatching {
                encryptedFile(encryptedConf).openFileInput().use { it.readBytes() }
            }.recoverCatching {
                recoverMisnamedCiphertext() ?: throw it
            }.getOrThrow()
            try {
                plaintextConf.writeBytes(plain)
                // The plaintext briefly holds tokens/passwords; tighten to owner-only so
                // it isn't group-readable for the daemon's lifetime (defence-in-depth on
                // top of the per-UID sandbox). The leftover (if the process is hard-killed
                // mid-session) is deleted by the delete-then-decrypt at the top above.
                restrictToOwner(plaintextConf)
            } finally {
                // Don't leave the decrypted credential bytes lingering in a heap buffer.
                plain.fill(0)
            }
            plaintextConf
        }
    }

    /**
     * Recovers a config written by the earlier buggy [writeEncryptedAtomically],
     * which encrypted under the temp name `rclone.conf.enc.tmp` before renaming —
     * so the stored ciphertext's associated data is that temp name, not the final
     * one. We decrypt under the old name (via a copy carrying it), and on success
     * re-encrypt with the correct name so the next open uses the normal path.
     * Returns the recovered plaintext bytes, or null if this isn't a recoverable
     * (old-AAD) ciphertext. Must be called holding [ioLock].
     */
    private fun recoverMisnamedCiphertext(): ByteArray? {
        val misnamed = File(encryptedConf.parentFile, "${encryptedConf.name}.tmp")
        return runCatching {
            encryptedConf.copyTo(misnamed, overwrite = true)
            val bytes = encryptedFile(misnamed).openFileInput().use { it.readBytes() }
            // Guard against healing/returning an empty config: a blank plaintext would
            // start the daemon with no remotes and could trip a downstream wipe. An
            // authentic recovered config has content; if it's blank, treat recovery as
            // failed and leave the stored ciphertext untouched.
            if (bytes.isEmpty()) return@runCatching null
            // Heal: rewrite the store under the correct name (AAD) for next time.
            writeEncryptedAtomically(bytes)
            bytes
        }.getOrNull().also { runCatching { misnamed.delete() } }
    }

    /** Re-encrypts the (possibly daemon-modified) plaintext config and removes the temp file. */
    suspend fun persistAndCleanup() = withContext(dispatchers.io) {
        ioLock.withLock {
            if (plaintextConf.exists()) {
                val bytes = plaintextConf.readBytes()
                try {
                    writeEncryptedAtomically(bytes)
                } finally {
                    bytes.fill(0)
                }
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
            writeEncryptedAtomically(confContent.toByteArray())
        }
    }

    /**
     * Snapshots the raw *ciphertext* of the stored config for rollback, or null if no
     * config exists yet. Reads the encrypted bytes directly — it does NOT decrypt — so
     * no plaintext credentials ever enter memory (unlike [exportPlaintext]). Restore
     * with [restoreCiphertext]; since the file name is unchanged, the AEAD associated
     * data still matches and the restored bytes remain decryptable.
     */
    suspend fun snapshotCiphertext(): ByteArray? = withContext(dispatchers.io) {
        ioLock.withLock { if (encryptedConf.exists()) encryptedConf.readBytes() else null }
    }

    /** Restores a [snapshotCiphertext] snapshot. A null snapshot clears the config. */
    suspend fun restoreCiphertext(snapshot: ByteArray?) = withContext(dispatchers.io) {
        ioLock.withLock {
            if (snapshot == null) {
                if (encryptedConf.exists()) encryptedConf.delete()
            } else {
                // Crash-safe: stage the raw ciphertext in a same-name temp then atomically
                // move it over, so an interrupted restore can't leave a truncated store.
                // (Raw bytes — they are ALREADY encrypted under this file's name, so no
                // re-encryption; the shared name preserves the AEAD associated data.)
                val tmp = File(File(encryptedConf.parentFile, "enc-tmp").apply { mkdirs() }, encryptedConf.name)
                if (tmp.exists()) tmp.delete()
                tmp.writeBytes(snapshot)
                atomicMoveOver(tmp)
            }
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

    /**
     * Encrypts [bytes] into [encryptedConf] without ever leaving it half-written:
     * write to a temp file, then atomically replace. The previous ciphertext stays
     * intact until the new one is durable, so a Keystore/disk failure mid-write can't
     * destroy the credential store (the old delete-then-write did exactly that).
     *
     * CRITICAL: [EncryptedFile] uses the file's *name* as the AEAD associated data
     * (the HKDF `info` for the streaming key), so a ciphertext is only decryptable
     * by an [EncryptedFile] opened on a file with the **same name** — NOT just the
     * same Keystore master key. The temp file therefore shares [encryptedConf]'s
     * name and differs only in directory; renaming a differently-named temp (e.g.
     * `*.enc.tmp`) over it would derive a different key on read and fail with
     * "No matching key found for the ciphertext in the stream".
     */
    private fun writeEncryptedAtomically(bytes: ByteArray) {
        val tmpDir = File(encryptedConf.parentFile, "enc-tmp").apply { mkdirs() }
        // Same file name as the destination → same associated data → decryptable
        // after the move. Only the parent directory differs.
        val tmp = File(tmpDir, encryptedConf.name)
        if (tmp.exists()) tmp.delete()
        encryptedFile(tmp).openFileOutput().use { it.write(bytes) }
        atomicMoveOver(tmp)
    }

    /** Atomically replaces [encryptedConf] with [tmp] (falling back to a same-volume rename). */
    private fun atomicMoveOver(tmp: File) {
        runCatching {
            Files.move(
                tmp.toPath(),
                encryptedConf.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            // Filesystem without atomic-move support: a same-volume move is still a
            // rename (effectively atomic on Android's internal storage).
            Files.move(tmp.toPath(), encryptedConf.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Restricts [file] to owner read/write only (clears group/other access). */
    private fun restrictToOwner(file: File) {
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
}
