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

    // --- B4: multi-time nextOccurrenceMs ---

    @Test fun `multi-time picks nearest time later today`() {
        // From Monday 10:00, Mon @ [09:00, 11:00] — today at 11:00 is the closest.
        val times = listOf(9 * 60, 11 * 60)
        val next = SyncSchedule.nextOccurrenceMs(mon, times, ms(2024, 1, 1, 10, 0), utc)
        assertThat(next).isEqualTo(ms(2024, 1, 1, 11, 0))
    }

    @Test fun `multi-time rolls to next selected day when all times have passed today`() {
        // From Monday 10:00, Mon/Wed @ [09:00] — next is Wednesday 09:00.
        val times = listOf(9 * 60)
        val next = SyncSchedule.nextOccurrenceMs(mon or wed, times, ms(2024, 1, 1, 10, 0), utc)
        assertThat(next).isEqualTo(ms(2024, 1, 3, 9, 0))
    }

    @Test fun `multi-time returns minus-one for empty times list`() {
        assertThat(SyncSchedule.nextOccurrenceMs(mon, emptyList(), ms(2024, 1, 1, 10, 0), utc))
            .isEqualTo(-1)
    }

    @Test fun `multi-time returns minus-one for empty days mask`() {
        assertThat(SyncSchedule.nextOccurrenceMs(0, listOf(9 * 60), ms(2024, 1, 1, 10, 0), utc))
            .isEqualTo(-1)
    }

    // --- B4: isWithinBlackout ---

    @Test fun `isWithinBlackout same-day range - minute inside`() {
        // Window 08:00-22:00 (480-1320), minute 600 (10:00) is inside.
        assertThat(SyncSchedule.isWithinBlackout(600, 480, 1320)).isTrue()
    }

    @Test fun `isWithinBlackout same-day range - minute at start inclusive`() {
        assertThat(SyncSchedule.isWithinBlackout(480, 480, 1320)).isTrue()
    }

    @Test fun `isWithinBlackout same-day range - minute at end exclusive`() {
        assertThat(SyncSchedule.isWithinBlackout(1320, 480, 1320)).isFalse()
    }

    @Test fun `isWithinBlackout same-day range - minute outside`() {
        assertThat(SyncSchedule.isWithinBlackout(300, 480, 1320)).isFalse()
    }

    @Test fun `isWithinBlackout zero-width window always returns false`() {
        assertThat(SyncSchedule.isWithinBlackout(600, 600, 600)).isFalse()
    }

    @Test fun `isWithinBlackout overnight wrap - minute after start is inside`() {
        // Window 22:00-06:00 (1320-360), minute 1380 (23:00) is inside.
        assertThat(SyncSchedule.isWithinBlackout(1380, 1320, 360)).isTrue()
    }

    @Test fun `isWithinBlackout overnight wrap - minute before end is inside`() {
        // Window 22:00-06:00 (1320-360), minute 120 (02:00) is inside.
        assertThat(SyncSchedule.isWithinBlackout(120, 1320, 360)).isTrue()
    }

    @Test fun `isWithinBlackout overnight wrap - minute at end is outside`() {
        // Window 22:00-06:00, minute 360 (06:00) is outside (end is exclusive).
        assertThat(SyncSchedule.isWithinBlackout(360, 1320, 360)).isFalse()
    }

    @Test fun `isWithinBlackout overnight wrap - minute between end and start is outside`() {
        // Window 22:00-06:00, minute 720 (12:00) is in the middle of day, outside.
        assertThat(SyncSchedule.isWithinBlackout(720, 1320, 360)).isFalse()
    }

    // --- B4: shiftPastBlackout ---

    @Test fun `shiftPastBlackout inside same-day window shifts to end`() {
        // Candidate at 2024-01-01 10:00 UTC, window 08:00-22:00 (480-1320).
        val candidateMs = ms(2024, 1, 1, 10, 0)
        val shifted = SyncSchedule.shiftPastBlackout(candidateMs, 480, 1320, utc)
        // End is 22:00 same day.
        assertThat(shifted).isEqualTo(ms(2024, 1, 1, 22, 0))
    }

    @Test fun `shiftPastBlackout outside window returns unchanged`() {
        // Candidate at 2024-01-01 23:00 UTC, window 08:00-22:00 (480-1320).
        val candidateMs = ms(2024, 1, 1, 23, 0)
        val shifted = SyncSchedule.shiftPastBlackout(candidateMs, 480, 1320, utc)
        assertThat(shifted).isEqualTo(candidateMs)
    }

    @Test fun `shiftPastBlackout overnight wrap - inside shifts to end on next day`() {
        // Candidate at 2024-01-01 23:00 UTC, window 22:00-06:00 (1320-360).
        // End (06:00) on the NEXT day.
        val candidateMs = ms(2024, 1, 1, 23, 0)
        val shifted = SyncSchedule.shiftPastBlackout(candidateMs, 1320, 360, utc)
        assertThat(shifted).isEqualTo(ms(2024, 1, 2, 6, 0))
    }

    @Test fun `shiftPastBlackout zero-width window returns unchanged`() {
        val candidateMs = ms(2024, 1, 1, 9, 0)
        val shifted = SyncSchedule.shiftPastBlackout(candidateMs, 540, 540, utc)
        assertThat(shifted).isEqualTo(candidateMs)
    }

    // --- B4: multi-time describe and cronString ---

    @Test fun `describe multi-time formats all times`() {
        // Mon + [02:00, 14:00]
        val desc = SyncSchedule.describe(mon, listOf(120, 840))
        assertThat(desc).isEqualTo("Mon at 02:00, 14:00")
    }

    @Test fun `describe multi-time daily prefix when all days selected`() {
        val desc = SyncSchedule.describe(0x7F, listOf(540))
        assertThat(desc).isEqualTo("Daily at 09:00")
    }

    @Test fun `describe multi-time returns null for empty times`() {
        assertThat(SyncSchedule.describe(mon, emptyList())).isNull()
    }

    @Test fun `cronString multi-time produces pipe-separated expressions`() {
        // Mon @ [02:00, 14:00] → "0 2 * * 1 | 0 14 * * 1"
        val cron = SyncSchedule.cronString(mon, listOf(120, 840))
        assertThat(cron).isEqualTo("0 2 * * 1 | 0 14 * * 1")
    }

    @Test fun `cronString multi-time returns null for empty times`() {
        assertThat(SyncSchedule.cronString(mon, emptyList())).isNull()
    }
}
