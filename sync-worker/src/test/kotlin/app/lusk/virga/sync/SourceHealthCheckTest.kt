package app.lusk.virga.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SourceHealthCheckTest {
    @Test
    fun `timed-out probe yields a replace-the-card message`() {
        val msg = preflightFailureMessage(SourceHealthCheck.HealthResult.TIMED_OUT)
        assertThat(msg).isNotNull()
        assertThat(msg!!).contains("card")
    }

    @Test
    fun `unreadable probe yields a re-select message`() {
        assertThat(preflightFailureMessage(SourceHealthCheck.HealthResult.UNREADABLE)).isNotNull()
    }

    @Test
    fun `healthy probe yields no message`() {
        assertThat(preflightFailureMessage(SourceHealthCheck.HealthResult.OK)).isNull()
    }
}
