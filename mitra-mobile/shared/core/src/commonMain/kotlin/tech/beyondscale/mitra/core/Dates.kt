package tech.beyondscale.mitra.core

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus

/* Ports of mitra-frontend/src/lib/dates.ts — days are ISO strings ("2026-09-17") everywhere. */

private val DOW_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val MON_LONG = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

fun addDays(iso: String, n: Int): String = LocalDate.parse(iso).plus(n, DateTimeUnit.DAY).toString()

/** "Tuesday, 13 October 2026" */
fun fmtLong(iso: String): String {
    val d = LocalDate.parse(iso)
    return "${DOW_LONG[d.dayOfWeek.isoDayNumber - 1]}, ${d.day} ${MON_LONG[d.month.number - 1]} ${d.year}"
}

/** "4:05 PM" */
fun fmtClock(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return "$h12:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
}

fun greeting(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}
