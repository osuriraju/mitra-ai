package tech.beyondscale.mitra.testing

import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.AppClock
import kotlin.time.Instant

class FixedClock(var instant: Instant, override val zone: TimeZone = TimeZone.UTC) : AppClock {
    override fun now(): Instant = instant
}
