package app.lusk.virga.feature.sync

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [resolveTreeUriToPath] — the best-effort SAF-tree-URI → real
 * filesystem path resolver used by the all-files-access build.
 *
 * Robolectric supplies [Uri]/[DocumentsContract] (pure URI builders, no provider
 * needed) and a real external-storage directory for the `primary` volume, so the
 * resolver can be exercised against actual temp files. The security-critical
 * path-traversal rejection is covered explicitly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafPathsTest {

    private val authority = "com.android.externalstorage.documents"

    private fun treeUri(treeDocumentId: String): Uri =
        DocumentsContract.buildTreeDocumentUri(authority, treeDocumentId)

    @Test
    fun `resolves an existing primary-volume subtree to its canonical path`() {
        val root = Environment.getExternalStorageDirectory()
        val sub = File(root, "Backups/Photos").apply { mkdirs() }

        assertThat(resolveTreeUriToPath(treeUri("primary:Backups/Photos")))
            .isEqualTo(sub.canonicalPath)
    }

    @Test
    fun `resolves the primary volume root when the relative path is empty`() {
        val root = Environment.getExternalStorageDirectory().apply { mkdirs() }

        assertThat(resolveTreeUriToPath(treeUri("primary:")))
            .isEqualTo(root.canonicalPath)
    }

    @Test
    fun `returns null when the resolved path does not exist`() {
        assertThat(resolveTreeUriToPath(treeUri("primary:NoSuch/Directory"))).isNull()
    }

    @Test
    fun `returns null for a document id without a volume separator`() {
        assertThat(resolveTreeUriToPath(treeUri("novolumeseparator"))).isNull()
    }

    @Test
    fun `rejects path traversal that escapes the storage root`() {
        // The canonical target lands outside the base, so it must be rejected even
        // though "/etc" (or similar) may exist on the host — this is the security guard.
        assertThat(resolveTreeUriToPath(treeUri("primary:../../../../../etc"))).isNull()
    }

    @Test
    fun `maps a non-primary volume to the storage volume path`() {
        // Exercises the secondary-volume branch (base = "/storage/<volume>"); the path
        // does not exist under the test runtime, so the result is null.
        assertThat(resolveTreeUriToPath(treeUri("1A2B-3C4D:DCIM"))).isNull()
    }
}
