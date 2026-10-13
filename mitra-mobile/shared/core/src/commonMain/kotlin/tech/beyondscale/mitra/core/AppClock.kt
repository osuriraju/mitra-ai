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
