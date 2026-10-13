package tech.beyondscale.mitra.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DatesTest {
    @Test fun fmtLongMatchesWeb() = assertEquals("Tuesday, 13 October 2026", fmtLong("2026-10-13"))

    @Test fun addDaysCrossesMonthAndLeapBoundaries() {
        assertEquals("2026-11-01", addDays("2026-10-31", 1))
        assertEquals("2026-02-28", addDays("2026-03-01", -1))
        assertEquals("2028-02-29", addDays("2028-02-28", 1))
    }

    @Test fun fmtClockUses12HourTime() {
        assertEquals("12:05 AM", fmtClock(0, 5))
        assertEquals("12:30 PM", fmtClock(12, 30))
        assertEquals("1:00 PM", fmtClock(13, 0))
        assertEquals("11:59 PM", fmtClock(23, 59))
    }

    @Test fun greetingBoundariesMatchWeb() {
        assertEquals("Good morning", greeting(11))
        assertEquals("Good afternoon", greeting(12))
        assertEquals("Good afternoon", greeting(16))
        assertEquals("Good evening", greeting(17))
    }
}
