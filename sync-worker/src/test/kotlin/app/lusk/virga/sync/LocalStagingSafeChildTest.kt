package app.lusk.virga.sync

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Security-focused coverage for [LocalStaging.safeChild] — the path-traversal
 * boundary that keeps SAF-staged copies app-private (audit sync-S finding).
 *
 * A DocumentsProvider can hand back arbitrary display names, so [safeChild] is
 * the gate that rejects any name resolving outside the staging dir. These are
 * pure tests over real temp dirs (no Android runtime), reaching the private
 * method by reflection so the canonical-path logic is exercised directly rather
 * than through a live provider.
 */
class LocalStagingSafeChildTest {

    private val staging = LocalStaging(mockk<Context>(relaxed = true))

    private val safeChild = LocalStaging::class.java
        .getDeclaredMethod("safeChild", File::class.java, String::class.java)
        .apply { isAccessible = true }

    private fun safeChild(dest: File, name: String?): File? =
        safeChild.invoke(staging, dest, name) as File?

    // --- legitimate children are accepted -----------------------------------

    @Test
    fun `plain file name resolves to a direct child`(@TempDir dest: File) {
        val result = safeChild(dest, "photo.jpg")
        assertThat(result).isNotNull()
        assertThat(result!!.parentFile!!.canonicalPath).isEqualTo(dest.canonicalPath)
        assertThat(result.name).isEqualTo("photo.jpg")
    }

    @Test
    fun `name with dots but no traversal is accepted`(@TempDir dest: File) {
        // "..." and "a..b" are valid file names — only the exact "." / ".." and
        // separators are unsafe, so these must pass.
        assertThat(safeChild(dest, "...")).isNotNull()
        assertThat(safeChild(dest, "a..b")).isNotNull()
        assertThat(safeChild(dest, "file.tar.gz")).isNotNull()
    }

    @Test
    fun `name with leading dot (hidden file) is accepted`(@TempDir dest: File) {
        assertThat(safeChild(dest, ".hidden")).isNotNull()
    }

    // --- traversal / escape attempts are rejected ---------------------------

    @Test
    fun `parent-dir name is rejected`(@TempDir dest: File) {
        assertThat(safeChild(dest, "..")).isNull()
    }

    @Test
    fun `current-dir name is rejected`(@TempDir dest: File) {
        assertThat(safeChild(dest, ".")).isNull()
    }

    @Test
    fun `name containing a forward-slash separator is rejected`(@TempDir dest: File) {
        // "../escape", "sub/child", and a trailing "evil/" all carry a separator.
        assertThat(safeChild(dest, "../escape")).isNull()
        assertThat(safeChild(dest, "sub/child")).isNull()
        assertThat(safeChild(dest, "evil/")).isNull()
    }

    @Test
    fun `name containing a backslash separator is rejected`(@TempDir dest: File) {
        assertThat(safeChild(dest, "..\\escape")).isNull()
        assertThat(safeChild(dest, "sub\\child")).isNull()
    }

    @Test
    fun `absolute-path name is rejected`(@TempDir dest: File) {
        // An absolute name contains separators, so it never resolves as a child.
        assertThat(safeChild(dest, "/etc/passwd")).isNull()
        assertThat(safeChild(dest, "/tmp/escape")).isNull()
    }

    @Test
    fun `null name is rejected`(@TempDir dest: File) {
        assertThat(safeChild(dest, null)).isNull()
    }

    @Test
    fun `empty name is rejected`(@TempDir dest: File) {
        assertThat(safeChild(dest, "")).isNull()
    }

    @Test
    fun `symlink escaping the dest via canonical mismatch is rejected`(@TempDir dest: File, @TempDir outside: File) {
        // A symlink whose simple name has no separators passes the cheap checks,
        // but its canonical path points outside dest — the canonicalPath.startsWith
        // guard is the last line of defense and must reject it.
        val outsideTarget = File(outside, "secret").apply { writeText("top secret") }
        val linkName = "innocent"
        val link = File(dest, linkName).toPath()
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(link, outsideTarget.toPath())
        }.isSuccess
        org.junit.jupiter.api.Assumptions.assumeTrue(
            created,
            "filesystem does not support symlinks; canonical-mismatch branch is instrumented-only here",
        )
        // The link's canonical path is the outside target, which is not under dest.
        assertThat(safeChild(dest, linkName)).isNull()
    }

    @Test
    fun `accepted child does not escape the dest canonical prefix`(@TempDir dest: File) {
        // Regression guard for the prefix check: every accepted child's canonical
        // path is strictly under dest + separator.
        val destPrefix = dest.canonicalPath + File.separator
        for (name in listOf("a", "b.txt", "nested.name", "...")) {
            val child = safeChild(dest, name) ?: continue
            assertThat(child.canonicalPath).startsWith(destPrefix)
        }
    }
}
