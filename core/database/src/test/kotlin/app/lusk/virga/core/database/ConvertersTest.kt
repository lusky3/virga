package app.lusk.virga.core.database

import app.lusk.virga.core.common.model.SyncDirection
import app.lusk.virga.core.common.model.SyncStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-JVM tests for [Converters] — no Android/Room needed. */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun direction_roundTrips() {
        for (d in SyncDirection.entries) {
            assertThat(converters.stringToDirection(converters.directionToString(d))).isEqualTo(d)
        }
    }

    @Test
    fun status_roundTrips() {
        for (s in SyncStatus.entries) {
            assertThat(converters.stringToStatus(converters.statusToString(s))).isEqualTo(s)
        }
    }

    @Test
    fun unknownDirection_fallsBackToUpload_insteadOfThrowing() {
        assertThat(converters.stringToDirection("NOT_A_REAL_NAME")).isEqualTo(SyncDirection.UPLOAD)
    }

    @Test
    fun unknownStatus_fallsBackToFailed_insteadOfThrowing() {
        assertThat(converters.stringToStatus("NOT_A_REAL_NAME")).isEqualTo(SyncStatus.FAILED)
    }
}
