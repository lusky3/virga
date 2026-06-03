package app.lusk.virga.update

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [isNewerVersion] — the semver compare that decides whether to show the
 * GitHub update banner to sideload/FOSS users. Lives in src/testFoss because the function
 * is in the foss source set. (Leading "v" is stripped by the caller, not this function.)
 */
class FossUpdateCheckerTest {

    @Test fun `strictly newer in each segment is newer`() {
        assertThat(isNewerVersion("2.0.0", "1.9.9")).isTrue()
        assertThat(isNewerVersion("1.3.0", "1.2.9")).isTrue()
        assertThat(isNewerVersion("1.2.1", "1.2.0")).isTrue()
    }

    @Test fun `equal version is not newer`() {
        assertThat(isNewerVersion("1.2.3", "1.2.3")).isFalse()
    }

    @Test fun `older version is not newer`() {
        assertThat(isNewerVersion("1.2.0", "1.2.1")).isFalse()
        assertThat(isNewerVersion("0.9.9", "1.0.0")).isFalse()
    }

    @Test fun `unequal segment counts compare by value, missing segments are zero`() {
        assertThat(isNewerVersion("1.2", "1.2.0")).isFalse()   // 1.2 == 1.2.0
        assertThat(isNewerVersion("1.2.1", "1.2")).isTrue()    // 1.2.1 > 1.2.0
        assertThat(isNewerVersion("1.2", "1.2.1")).isFalse()
    }

    @Test fun `non-numeric (pre-release) segments coerce to zero`() {
        // "1.2.0-rc1" → [1,2,0] (the "0-rc1" segment isn't an Int → 0), so it is treated
        // as equal to "1.2.0": a pre-release is NOT considered newer than its release, and
        // the release is NOT newer than the pre-release. This is the load-bearing rule.
        assertThat(isNewerVersion("1.2.0-rc1", "1.2.0")).isFalse()
        assertThat(isNewerVersion("1.2.0", "1.2.0-rc1")).isFalse()
        // A higher numeric major still wins despite a suffix.
        assertThat(isNewerVersion("2.0.0-beta", "1.0.0")).isTrue()
    }
}
