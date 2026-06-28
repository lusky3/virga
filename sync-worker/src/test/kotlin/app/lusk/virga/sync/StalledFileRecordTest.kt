package app.lusk.virga.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StalledFileRecordTest {
    @Test
    fun `appends the stalled file as a failed-files row`() {
        val merged = mergeStalledFile("a.txt\terror x", "DCIM/IMG_BAD.jpg")
        assertThat(merged).contains("a.txt\terror x")
        assertThat(merged).contains("DCIM/IMG_BAD.jpg\t")
        assertThat(merged.lines()).hasSize(2)
    }

    @Test
    fun `no-op when stalledFile is null`() {
        assertThat(mergeStalledFile("a.txt\terror x", null)).isEqualTo("a.txt\terror x")
    }

    @Test
    fun `does not duplicate an already-listed file`() {
        val merged = mergeStalledFile("DCIM/IMG_BAD.jpg\tboom", "DCIM/IMG_BAD.jpg")
        assertThat(merged.lines()).hasSize(1)
    }

    @Test
    fun `sanitises tabs and newlines in the stalled path so the row encoding is not corrupted`() {
        val merged = mergeStalledFile("", "weird\tname\nwith\rbreaks.jpg")
        // One row, and exactly one tab — the path\terror separator (embedded breaks collapsed).
        assertThat(merged.lines()).hasSize(1)
        assertThat(merged.count { it == '\t' }).isEqualTo(1)
    }
}
