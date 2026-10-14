package tech.beyondscale.mitra.core

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

interface AppClock {
    fun now(): Instant
    val zone: TimeZone
}

object SystemClock : AppClock {
    override fun now(): Instant = Clock.System.now()
    override val zone: TimeZone get() = TimeZone.currentSystemDefault()
}

fun AppClock.localNow(): LocalDateTime = now().toLocalDateTime(zone)

/** The device's local date as ISO ("2026-10-14") — what every "today" in the app means. */
fun AppClock.today(): String = localNow().date.toString()
