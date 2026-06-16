package app.lusk.virga.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure helpers for the calendar ("specific days & time") schedule. The schedule
 * is a weekday bitmask (Mon=bit0 … Sun=bit6) plus one or more local times-of-day.
 * WorkManager can't express "every Mon/Wed at 02:00 & 14:00", so the scheduler
 * computes the next matching instant and enqueues a one-shot, re-enqueuing after
 * each run.
 *
 * Blackout (quiet hours) semantics:
 * - A window [startMin, endMin) in minutes-of-day (0..1439) suppresses scheduled syncs.
 * - When startMin > endMin the window wraps midnight (e.g. 22:00–06:00 the next day).
 * - startMin == endMin is treated as "no window / disabled" (always outside).
 * - shiftPastBlackout moves a candidate that falls inside the window to the window END
 *   on the same day (non-wrap) or the next day (wrap, when the end is before the
 *   candidate's date-local start).
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
     * Multi-time overload: returns the soonest epoch-millis strictly after [fromMs]
     * across ALL [times] (minutes-of-day, 0..1439) on selected weekdays. Returns -1
     * if [daysMask] selects no days or [times] is empty.
     *
     * Resolution: scans days 0..8 from [fromMs]; for each selected day considers every
     * entry in [times] and picks the minimum candidate that is still after [from].
     */
    fun nextOccurrenceMs(
        daysMask: Int,
        times: List<Int>,
        fromMs: Long,
        zone: ZoneId,
    ): Long {
        if (daysMask == 0 || times.isEmpty()) return -1
        val from = Instant.ofEpochMilli(fromMs).atZone(zone)
        for (i in 0..8) {
            val date = from.toLocalDate().plusDays(i.toLong())
            if (!includesDay(daysMask, date.dayOfWeek.value)) continue
            // The earliest matching time on the first selected day wins: days farther
            // out can only be later, so the first non-null result is the answer.
            val best = earliestOnDate(date, times, from, zone)
            if (best != null) return best
        }
        return -1
    }

    /** Earliest of [times] (minutes-of-day) on [date] strictly after [from], or null. */
    private fun earliestOnDate(
        date: LocalDate,
        times: List<Int>,
        from: ZonedDateTime,
        zone: ZoneId,
    ): Long? {
        var best: Long? = null
        for (minuteOfDay in times) {
            val clamped = minuteOfDay.coerceIn(0, 1439)
            val candidate = date.atTime(clamped / 60, clamped % 60).atZone(zone)
            if (candidate.isAfter(from)) {
                val ms = candidate.toInstant().toEpochMilli()
                if (best == null || ms < best) best = ms
            }
        }
        return best
    }

    /**
     * Returns true if [minuteOfDay] falls inside the blackout window
     * [startMin, endMin). A zero-width window (startMin == endMin) is treated as
     * disabled and always returns false.
     *
     * Overnight-wrap case: when startMin > endMin the window crosses midnight,
     * e.g. startMin=1320 (22:00) endMin=360 (06:00). A minute is inside when it
     * is >= startMin OR < endMin.
     */
    fun isWithinBlackout(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return false
        val m = minuteOfDay.coerceIn(0, 1439)
        return if (startMin < endMin) {
            m >= startMin && m < endMin
        } else {
            m >= startMin || m < endMin
        }
    }

    /**
     * Returns [candidateMs] shifted to the blackout window END if it falls inside
     * the window, otherwise returns [candidateMs] unchanged.
     *
     * For a non-wrapping window the end time is on the same calendar day.
     * For an overnight-wrapping window (startMin > endMin) the end time is on
     * the NEXT calendar day relative to [candidateMs].
     */
    fun shiftPastBlackout(candidateMs: Long, startMin: Int, endMin: Int, zone: ZoneId): Long {
        val minuteOfDay = minuteOfDayAt(candidateMs, zone)
        if (!isWithinBlackout(minuteOfDay, startMin, endMin)) return candidateMs
        val candidateZdt = Instant.ofEpochMilli(candidateMs).atZone(zone)
        return endZdt(candidateZdt, startMin, endMin).toInstant().toEpochMilli()
    }

    private fun minuteOfDayAt(epochMs: Long, zone: ZoneId): Int {
        val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
        return zdt.hour * 60 + zdt.minute
    }

    private fun endZdt(candidateZdt: ZonedDateTime, startMin: Int, endMin: Int): ZonedDateTime {
        val sameDay = candidateZdt.toLocalDate().atTime(endMin / 60, endMin % 60).atZone(candidateZdt.zone)
        val minuteOfDay = candidateZdt.hour * 60 + candidateZdt.minute
        // Overnight wrap (startMin >= endMin): a candidate in the EVENING portion
        // (>= startMin, e.g. 23:00 of a 22:00–06:00 window) ends the NEXT day; a
        // MORNING-portion candidate (< endMin, e.g. 02:00) ends the SAME day. Only
        // the evening portion needs +1 day — adding it unconditionally pushed morning
        // candidates a full 24h too far.
        return if (startMin >= endMin && minuteOfDay >= startMin) sameDay.plusDays(1) else sameDay
    }

    /**
     * The equivalent 5-field cron expression for display, e.g. Mon/Wed/Fri at
     * 02:00 → "0 2 * * 1,3,5". Cron day-of-week uses 0=Sun … 6=Sat; all seven
     * days collapses to "*". Returns null when no days are selected.
     *
     * With multiple times, uses the first time entry (display approximation).
     */
    fun cronString(daysMask: Int, hour: Int, minute: Int): String? {
        if (daysMask == 0) return null
        val dow = buildDowString(daysMask)
        return "$minute $hour * * $dow"
    }

    /** Multi-time variant: returns one cron expression per time, comma-separated. */
    fun cronString(daysMask: Int, times: List<Int>): String? {
        if (daysMask == 0 || times.isEmpty()) return null
        val dow = buildDowString(daysMask)
        return times.joinToString(" | ") { minuteOfDay ->
            val h = minuteOfDay / 60
            val m = minuteOfDay % 60
            "$m $h * * $dow"
        }
    }

    private fun buildDowString(daysMask: Int): String =
        if (daysMask and 0x7F == 0x7F) {
            "*"
        } else {
            (1..7)
                .filter { includesDay(daysMask, it) }
                .map { if (it == 7) 0 else it }
                .sorted()
                .joinToString(",")
        }

    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /**
     * A friendly one-line summary for list/summary UIs, e.g. "Mon, Wed, Fri at
     * 02:00" or "Daily at 09:00". Returns null when no days are selected.
     */
    fun describe(daysMask: Int, hour: Int, minute: Int): String? {
        if (daysMask == 0) return null
        val time = formatTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        val daysLabel = buildDaysLabel(daysMask)
        return "$daysLabel at $time"
    }

    /** Multi-time variant: "Mon, Wed at 02:00, 14:00" or "Daily at 02:00, 14:00". */
    fun describe(daysMask: Int, times: List<Int>): String? {
        if (daysMask == 0 || times.isEmpty()) return null
        val timesLabel = times.joinToString(", ") { minuteOfDay ->
            formatTime(minuteOfDay / 60, minuteOfDay % 60)
        }
        val daysLabel = buildDaysLabel(daysMask)
        return "$daysLabel at $timesLabel"
    }

    private fun buildDaysLabel(daysMask: Int): String =
        if (daysMask and 0x7F == 0x7F) {
            "Daily"
        } else {
            (1..7).filter { includesDay(daysMask, it) }.joinToString(", ") { DAY_NAMES[it - 1] }
        }

    private fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)
}
