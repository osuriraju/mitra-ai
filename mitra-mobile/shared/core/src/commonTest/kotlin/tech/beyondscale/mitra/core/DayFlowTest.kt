package tech.beyondscale.mitra.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DayFlowTest {
    // core can't use shared:testing (it depends on core), so a local clock.
    private class TestClock(var instant: Instant, override val zone: TimeZone) : AppClock {
        override fun now(): Instant = instant
    }

    private val ist = TimeZone.of("Asia/Kolkata")

    @Test fun todayIsTheLocalDateNotUtc() {
        // 19:00 UTC on the 13th is 00:30 IST on the 14th.
        assertEquals("2026-10-14", TestClock(Instant.parse("2026-10-13T19:00:00Z"), ist).today())
    }

    @Test fun nextMidnightIsLocal() {
        val clock = TestClock(Instant.parse("2026-10-13T18:00:00Z"), ist) // 23:30 IST
        assertEquals(Instant.parse("2026-10-13T18:30:00Z").toEpochMilliseconds(), clock.nextMidnightEpochMs())
    }

    @Test fun emitsAgainAtLocalMidnight() = runTest {
        val clock = TestClock(Instant.parse("2026-10-13T18:00:00Z"), ist) // 23:30 IST
        val seen = mutableListOf<String>()
        backgroundScope.launch { clock.dayFlow().collect { seen += it } }
        runCurrent()
        assertEquals(listOf("2026-10-13"), seen)

        clock.instant = Instant.parse("2026-10-13T18:30:00.002Z") // just past local midnight
        advanceTimeBy(30 * 60_000L + 2)
        runCurrent()
        assertEquals(listOf("2026-10-13", "2026-10-14"), seen)
    }
}
