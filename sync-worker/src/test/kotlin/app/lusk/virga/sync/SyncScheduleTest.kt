package app.lusk.virga.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SyncScheduleTest {

    private val utc = ZoneId.of("UTC")
    // Weekday bitmask bits: Mon=bit0 … Sun=bit6.
    private val mon = 1 shl 0
    private val wed = 1 shl 2
    private val fri = 1 shl 4
    private val sun = 1 shl 6

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, utc).toInstant().toEpochMilli()

    @Test fun `next occurrence later today when time not yet passed`() {
        // 2024-01-01 is a Monday. From 10:00, Monday 11:00 is still today.
        val next = SyncSchedule.nextOccurrenceMs(mon, 11, 0, ms(2024, 1, 1, 10, 0), utc)
        assertThat(next).isEqualTo(ms(2024, 1, 1, 11, 0))
    }

    @Test fun `next occurrence rolls to next week when today's time has passed`() {
        // From Monday 10:00 with a 09:00 Monday schedule -> next Monday (the 8th).
        val next = SyncSchedule.nextOccurrenceMs(mon, 9, 0, ms(2024, 1, 1, 10, 0), utc)
        assertThat(next).isEqualTo(ms(2024, 1, 8, 9, 0))
    }

    @Test fun `next occurrence picks the nearest selected weekday`() {
        // From Monday 10:00, Mon/Wed/Fri @ 02:00 -> Wednesday the 3rd at 02:00.
        val next = SyncSchedule.nextOccurrenceMs(mon or wed or fri, 2, 0, ms(2024, 1, 1, 10, 0), utc)
        assertThat(next).isEqualTo(ms(2024, 1, 3, 2, 0))
    }

    @Test fun `empty mask returns -1`() {
        assertThat(SyncSchedule.nextOccurrenceMs(0, 9, 0, ms(2024, 1, 1, 10, 0), utc)).isEqualTo(-1)
    }

    @Test fun `cron string maps weekdays with ISO Sunday to cron 0`() {
        assertThat(SyncSchedule.cronString(mon or wed or fri, 2, 0)).isEqualTo("0 2 * * 1,3,5")
        assertThat(SyncSchedule.cronString(sun, 0, 0)).isEqualTo("0 0 * * 0")
    }

    @Test fun `cron string collapses all seven days to star`() {
        assertThat(SyncSchedule.cronString(0x7F, 9, 30)).isEqualTo("30 9 * * *")
    }

    @Test fun `cron string is null when no days selected`() {
        assertThat(SyncSchedule.cronString(0, 9, 0)).isNull()
    }
}
