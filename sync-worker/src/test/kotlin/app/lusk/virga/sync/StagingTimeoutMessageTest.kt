package app.lusk.virga.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StagingTimeoutMessageTest {
    @Test
    fun `warns when files timed out during staging`() {
        val msg = stagingTimeoutWarning(2)
        assertThat(msg).isNotNull()
        assertThat(msg!!).contains("2")
        assertThat(msg).contains("card")
    }

    @Test
    fun `no warning when nothing timed out`() {
        assertThat(stagingTimeoutWarning(0)).isNull()
    }
}
