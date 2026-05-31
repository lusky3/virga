package app.lusk.virga.sync

import java.time.Instant
import java.time.ZoneId

/**
 * Pure helpers for the calendar ("specific days & time") schedule. The schedule
 * is a weekday bitmask (Mon=bit0 … Sun=bit6) plus a local time-of-day. WorkManager
 * can't express "every Mon/Wed at 02:00", so the scheduler computes the next
 * matching instant and enqueues a one-shot, re-enqueuing after each run.
 */
object SyncSchedule {

    /** True if [daysMask] selects [isoDayOfWeek] (1=Mon … 7=Sun). */
    fun includesDay(daysMask: Int, isoDayOfWeek: Int): Boolean =
        daysMask and (1 shl (isoDayOfWeek - 1)) != 0

    /**
     * Epoch-millis of the next time [hour]:[minute] falls on a selected weekday,
     * strictly after [fromMs] in [zone]. Returns -1 if [daysMask] selects no days.
     * Scans up to 8 days so "only today, but the time already passed" rolls to the
     * same weekday next week.
     */
    fun nextOccurrenceMs(
        daysMask: Int,
        hour: Int,
        minute: Int,
        fromMs: Long,
        zone: ZoneId,
    ): Long {
        if (daysMask == 0) return -1
        val from = Instant.ofEpochMilli(fromMs).atZone(zone)
        for (i in 0..8) {
            val date = from.toLocalDate().plusDays(i.toLong())
            if (!includesDay(daysMask, date.dayOfWeek.value)) continue
            val candidate = date.atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).atZone(zone)
            if (candidate.isAfter(from)) return candidate.toInstant().toEpochMilli()
        }
        return -1
    }

    /**
     * The equivalent 5-field cron expression for display, e.g. Mon/Wed/Fri at
     * 02:00 → "0 2 * * 1,3,5". Cron day-of-week uses 0=Sun … 6=Sat; all seven
     * days collapses to "*". Returns null when no days are selected.
     */
    fun cronString(daysMask: Int, hour: Int, minute: Int): String? {
        if (daysMask == 0) return null
        val dow = if (daysMask and 0x7F == 0x7F) {
            "*"
        } else {
            (1..7) // ISO Mon..Sun
                .filter { includesDay(daysMask, it) }
                .map { if (it == 7) 0 else it } // ISO Sun(7) -> cron 0
                .sorted()
                .joinToString(",")
        }
        return "$minute $hour * * $dow"
    }

    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /**
     * A friendly one-line summary for list/summary UIs, e.g. "Mon, Wed, Fri at
     * 02:00" or "Daily at 09:00". Returns null when no days are selected.
     */
    fun describe(daysMask: Int, hour: Int, minute: Int): String? {
        if (daysMask == 0) return null
        val time = "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        if (daysMask and 0x7F == 0x7F) return "Daily at $time"
        val days = (1..7).filter { includesDay(daysMask, it) }.joinToString(", ") { DAY_NAMES[it - 1] }
        return "$days at $time"
    }
}
