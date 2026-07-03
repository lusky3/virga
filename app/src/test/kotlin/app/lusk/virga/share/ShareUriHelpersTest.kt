package app.lusk.virga.share

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files

/**
 * Unit tests for the SAF helper functions in ShareUriHelpers.kt —
 * [safDisplayName], [copyFromSafUri], and [stageUri]. ([sanitizeSafName] is
 * covered separately by SanitizeSafNameTest.)
 *
 * [ShareReceiverViewModelTest] stubs these out with `mockkStatic`, so they are
 * exercised for real only here. Robolectric supplies [Uri]/[MatrixCursor]; the
 * [Context]/[ContentResolver] are MockK stubs so no live provider is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareUriHelpersTest {

    private val resolver: ContentResolver = mockk()
    private val context: Context = mockk {
        every { contentResolver } returns resolver
    }

    /** A cursor over the DISPLAY_NAME column: one row with [name], or empty when null. */
    private fun displayNameCursor(name: String?): MatrixCursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
            if (name != null) addRow(arrayOf<Any?>(name))
        }

    // --- safDisplayName ---

    @Test
    fun `safDisplayName returns the OpenableColumns display name`() {
        val uri = Uri.parse("content://media/external/file/42")
        every { resolver.query(uri, any(), null, null, null) } returns displayNameCursor("photo.jpg")

        assertThat(safDisplayName(context, uri)).isEqualTo("photo.jpg")
    }

    @Test
    fun `safDisplayName falls back to the last path segment when the cursor is empty`() {
        val uri = Uri.parse("content://media/external/file/report.pdf")
        every { resolver.query(uri, any(), null, null, null) } returns displayNameCursor(null)

        assertThat(safDisplayName(context, uri)).isEqualTo("report.pdf")
    }

    @Test
    fun `safDisplayName falls back to upload when cursor is null and there is no path segment`() {
        val uri = Uri.parse("content://authority-only")
        every { resolver.query(uri, any(), null, null, null) } returns null

        assertThat(safDisplayName(context, uri)).isEqualTo("upload")
    }

    // --- copyFromSafUri ---

    @Test
    fun `copyFromSafUri writes the source bytes into the destination file`() {
        val uri = Uri.parse("content://media/external/file/1")
        val bytes = "hello world".toByteArray()
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(bytes)
        val dest = Files.createTempFile("copy", ".bin").toFile().also { it.deleteOnExit() }

        copyFromSafUri(context, uri, dest)

        assertThat(dest.readBytes()).isEqualTo(bytes)
    }

    @Test(expected = IOException::class)
    fun `copyFromSafUri throws when the input stream cannot be opened`() {
        val uri = Uri.parse("content://media/external/file/1")
        every { resolver.openInputStream(uri) } returns null
        val dest = Files.createTempFile("copy", ".bin").toFile().also { it.deleteOnExit() }

        copyFromSafUri(context, uri, dest)
    }

    // --- stageUri ---

    @Test
    fun `stageUri stages the content under a sanitized single-segment name`() {
        val uri = Uri.parse("content://media/external/file/1")
        // A hostile display name with traversal + separators must be reduced to a safe segment.
        every { resolver.query(uri, any(), null, null, null) } returns displayNameCursor("../../etc/evil.txt")
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream("data".toByteArray())
        val dir = Files.createTempDirectory("stage").toFile().also { it.deleteOnExit() }

        val staged = stageUri(context, uri, dir)

        assertThat(staged).isNotNull()
        assertThat(staged!!.parentFile).isEqualTo(dir)
        assertThat(staged.name).doesNotContain("/")
        assertThat(staged.name).doesNotContain("..")
        assertThat(staged.readText()).isEqualTo("data")
    }

    @Test
    fun `stageUri returns null when the source cannot be read`() {
        val uri = Uri.parse("content://media/external/file/1")
        every { resolver.query(uri, any(), null, null, null) } returns displayNameCursor("f.txt")
        every { resolver.openInputStream(uri) } throws IOException("unreadable")
        val dir = Files.createTempDirectory("stage").toFile().also { it.deleteOnExit() }

        assertThat(stageUri(context, uri, dir)).isNull()
    }

    @Test
    fun `stageUri returns null when access is denied`() {
        val uri = Uri.parse("content://media/external/file/1")
        every { resolver.query(uri, any(), null, null, null) } returns displayNameCursor("f.txt")
        every { resolver.openInputStream(uri) } throws SecurityException("denied")
        val dir = Files.createTempDirectory("stage").toFile().also { it.deleteOnExit() }

        assertThat(stageUri(context, uri, dir)).isNull()
    }
}
