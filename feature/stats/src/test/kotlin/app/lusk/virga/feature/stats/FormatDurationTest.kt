package app.lusk.virga.feature.stats

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM JUnit5 tests for [formatDuration], which is `internal` and in the same
 * package, so it is directly callable here without reflection.
 */
class FormatDurationTest {

    @Test
    fun `should return dash when millis is zero`() {
        assertThat(formatDuration(0L)).isEqualTo("—")
    }

    @Test
    fun `should return dash when millis is negative`() {
        assertThat(formatDuration(-1L)).isEqualTo("—")
    }

    @Test
    fun `should return dash when millis is large negative`() {
        assertThat(formatDuration(-60_000L)).isEqualTo("—")
    }

    @Test
    fun `should return less than 1m when millis is below one minute`() {
        assertThat(formatDuration(59_999L)).isEqualTo("< 1m")
    }

    @Test
    fun `should return 1m when millis is exactly one minute`() {
        assertThat(formatDuration(60_000L)).isEqualTo("1m")
    }

    @Test
    fun `should return 1h when millis is exactly one hour`() {
        assertThat(formatDuration(3_600_000L)).isEqualTo("1h")
    }

    @Test
    fun `should return 1h 1m when millis is one hour and one minute`() {
        assertThat(formatDuration(3_660_000L)).isEqualTo("1h 1m")
    }

    @Test
    fun `should return 2h 2m when millis is two hours and two minutes`() {
        assertThat(formatDuration(7_320_000L)).isEqualTo("2h 2m")
    }
}
