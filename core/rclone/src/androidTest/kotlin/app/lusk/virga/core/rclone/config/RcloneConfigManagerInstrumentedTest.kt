package app.lusk.virga.core.rclone.config

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lusk.virga.core.common.dispatchers.DefaultDispatcherProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Round-trips the real [RcloneConfigManager] against the on-device Android
 * Keystore + Tink `EncryptedFile`. This is the regression guard for the
 * credential data-loss bug: a config encrypted via the atomic write must remain
 * decryptable on a *cold* reopen. Before the fix (which renamed the ciphertext
 * across differing names, changing the AEAD associated data) the reopen failed
 * with "No matching key found for the ciphertext in the stream".
 *
 * Instrumented (not a JVM unit test) because `EncryptedFile`/`MasterKey` require
 * a real Keystore provider, which Robolectric does not reliably supply.
 */
@RunWith(AndroidJUnit4::class)
class RcloneConfigManagerInstrumentedTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var manager: RcloneConfigManager

    private val sampleConfig = "[gdrive]\ntype = drive\ntoken = {\"access_token\":\"abc\"}\n"

    @Before
    fun setUp() {
        // Isolate from any real config on the test device.
        File(ctx.noBackupFilesDir, "rclone.conf.enc").delete()
        File(ctx.noBackupFilesDir, "rclone.conf").delete()
        File(ctx.noBackupFilesDir, "rclone.conf.enc.tmp").delete()
        File(ctx.noBackupFilesDir, "enc-tmp").deleteRecursively()
        manager = RcloneConfigManager(ctx, DefaultDispatcherProvider())
    }

    @Test
    fun import_then_decrypt_round_trips() = runBlocking {
        manager.import(sampleConfig)
        val decrypted = manager.decryptForDaemon().readText()
        assertThat(decrypted).isEqualTo(sampleConfig)
    }

    /** The exact failure scenario: import, use, persist, then COLD reopen. */
    @Test
    fun survives_persist_and_cold_reopen() = runBlocking {
        manager.import(sampleConfig)
        manager.decryptForDaemon()
        manager.persistAndCleanup() // re-encrypts via the atomic write + drops plaintext
        // Fresh decrypt of the persisted ciphertext — this is what broke before.
        val reopened = manager.decryptForDaemon().readText()
        assertThat(reopened).isEqualTo(sampleConfig)
    }

    /** rclone rewrites the plaintext (e.g. token refresh); the change must persist. */
    @Test
    fun daemon_modified_config_is_persisted_and_reread() = runBlocking {
        manager.import(sampleConfig)
        val plaintext = manager.decryptForDaemon()
        val updated = sampleConfig + "[mys3]\ntype = s3\n"
        plaintext.writeText(updated)
        manager.persistAndCleanup()
        assertThat(manager.decryptForDaemon().readText()).isEqualTo(updated)
    }

    @Test
    fun export_returns_the_decrypted_text() = runBlocking {
        manager.import(sampleConfig)
        assertThat(manager.exportPlaintext()).isEqualTo(sampleConfig)
    }
}
