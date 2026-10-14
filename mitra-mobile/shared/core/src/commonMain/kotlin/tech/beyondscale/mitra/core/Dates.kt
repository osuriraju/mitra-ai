package tech.beyondscale.mitra.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus

/* Ports of mitra-frontend/src/lib/dates.ts — days are ISO strings ("2026-09-17") everywhere. */

private val DOW_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val DOW_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MON_LONG = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val MON_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun addDays(iso: String, n: Int): String = LocalDate.parse(iso).plus(n, DateTimeUnit.DAY).toString()

/** Whole days from [b] to [a] — positive when [a] is later (web `diffDays(a, b)`). */
fun diffDays(a: String, b: String): Int = LocalDate.parse(b).daysUntil(LocalDate.parse(a))

/** Monday-based weekday index 0..6 (web `dowMon`). */
fun dowMon(iso: String): Int = LocalDate.parse(iso).dayOfWeek.isoDayNumber - 1

/**
 * Month arithmetic with JavaScript `Date#setUTCMonth` semantics: the day is kept and overflows into the
 * next month (31 Jan + 1 month = 3 Mar). The server's recurring-task `nextDue` behaves this way.
 */
fun addMonthsJs(iso: String, n: Int): String {
    val d = LocalDate.parse(iso)
    return LocalDate(d.year, d.month, 1).plus(n, DateTimeUnit.MONTH).plus(d.day - 1, DateTimeUnit.DAY).toString()
}

/** "Tuesday, 13 October 2026" */
fun fmtLong(iso: String): String {
    val d = LocalDate.parse(iso)
    return "${DOW_LONG[d.dayOfWeek.isoDayNumber - 1]}, ${d.day} ${MON_LONG[d.month.number - 1]} ${d.year}"
}

/** "Today" · "Tomorrow" · "Yesterday" · "Mon 21 Sep" — relative to [today] (the device's local date). */
fun fmtDay(iso: String, today: String, relative: Boolean = true): String {
    if (relative) when (diffDays(iso, today)) {
        0 -> return "Today"
        1 -> return "Tomorrow"
        -1 -> return "Yesterday"
    }
    val d = LocalDate.parse(iso)
    return "${DOW_SHORT[d.dayOfWeek.isoDayNumber - 1]} ${d.day} ${MON_SHORT[d.month.number - 1]}"
}

/** "4:05 PM" */
fun fmtClock(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return "$h12:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
}

/** "17:00" → "5:00 PM"; empty for null or anything that isn't HH:MM. */
fun fmtTime(t: String?): String {
    val parts = t?.split(":") ?: return ""
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return ""
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return ""
    return fmtClock(h, m)
}

/** 45 → "45m" · 60 → "1h" · 65 → "1h 05m" (web `minsToHm`). */
fun minsToHm(m: Int): String =
    if (m >= 60) "${m / 60}h ${if (m % 60 != 0) (m % 60).toString().padStart(2, '0') + "m" else ""}".trim() else "${m}m"

fun greeting(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}
