package app.lusk.virga.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for the top-level [releaseNotes] and [releaseNotesFor] functions in
 * Changelog.kt, using Robolectric real [Resources] so the actual string-array
 * entries in res/values/strings.xml are resolved without a device.
 *
 * Runs under :app:testFossDebugUnitTest (and testPlayDebugUnitTest) because it
 * lives in src/test — the shared, non-flavored test source set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangelogTest {

    private val resources get() = RuntimeEnvironment.getApplication().resources

    // --- releaseNotes ---

    @Test
    fun `releaseNotes returns three entries`() {
        val notes = releaseNotes(resources)

        assertThat(notes).hasSize(3)
    }

    @Test
    fun `releaseNotes first entry is version 0_3_0 (newest first)`() {
        val notes = releaseNotes(resources)

        assertThat(notes.first().versionName).isEqualTo("0.3.0")
    }

    @Test
    fun `releaseNotes last entry is version 0_1_0`() {
        val notes = releaseNotes(resources)

        assertThat(notes.last().versionName).isEqualTo("0.1.0")
    }

    @Test
    fun `releaseNotes 0_3_0 entry has non-empty notes`() {
        val notes = releaseNotes(resources)

        assertThat(notes.first().notes).isNotEmpty()
    }

    @Test
    fun `releaseNotes every entry has a non-empty notes list`() {
        val notes = releaseNotes(resources)

        notes.forEach { entry ->
            assertThat(entry.notes).isNotEmpty()
        }
    }

    // --- releaseNotesFor ---

    @Test
    fun `releaseNotesFor returns the correct entry for version 0_2_0`() {
        val entry = releaseNotesFor("0.2.0", resources)

        assertThat(entry).isNotNull()
        assertThat(entry!!.versionName).isEqualTo("0.2.0")
    }

    @Test
    fun `releaseNotesFor returns an entry with non-empty notes for 0_2_0`() {
        val entry = releaseNotesFor("0.2.0", resources)

        assertThat(entry!!.notes).isNotEmpty()
    }

    @Test
    fun `releaseNotesFor returns null for an unknown version`() {
        val entry = releaseNotesFor("9.9.9", resources)

        assertThat(entry).isNull()
    }

    @Test
    fun `releaseNotesFor returns null for an empty version string`() {
        val entry = releaseNotesFor("", resources)

        assertThat(entry).isNull()
    }
}
