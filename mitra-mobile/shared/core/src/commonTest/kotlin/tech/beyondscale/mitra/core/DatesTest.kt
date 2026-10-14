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

    @Test fun fmtDayIsRelativeToTheGivenToday() {
        val today = "2026-10-14" // a Wednesday
        assertEquals("Today", fmtDay("2026-10-14", today))
        assertEquals("Tomorrow", fmtDay("2026-10-15", today))
        assertEquals("Yesterday", fmtDay("2026-10-13", today))
        assertEquals("Mon 19 Oct", fmtDay("2026-10-19", today))
        assertEquals("Wed 14 Oct", fmtDay("2026-10-14", today, relative = false))
    }

    @Test fun fmtTimeMatchesWebAndToleratesJunk() {
        assertEquals("5:00 PM", fmtTime("17:00"))
        assertEquals("12:05 AM", fmtTime("00:05"))
        assertEquals("", fmtTime(null))
        assertEquals("", fmtTime("soon"))
    }

    @Test fun minsToHmMatchesWeb() {
        assertEquals("0m", minsToHm(0))
        assertEquals("45m", minsToHm(45))
        assertEquals("1h", minsToHm(60))
        assertEquals("1h 05m", minsToHm(65))
        assertEquals("2h 30m", minsToHm(150))
    }

    @Test fun diffDaysAndDowMon() {
        assertEquals(7, diffDays("2026-10-20", "2026-10-13"))
        assertEquals(-1, diffDays("2026-10-12", "2026-10-13"))
        assertEquals(1, dowMon("2026-10-13")) // Tuesday
        assertEquals(6, dowMon("2026-10-18")) // Sunday
    }

    @Test fun addMonthsJsOverflowsLikeTheServer() {
        assertEquals("2026-03-03", addMonthsJs("2026-01-31", 1)) // JS: 31 Feb → 3 Mar
        assertEquals("2026-11-15", addMonthsJs("2026-10-15", 1))
        assertEquals("2027-03-02", addMonthsJs("2026-11-30", 3))
    }

    @Test fun monthHelpers() {
        assertEquals(29, daysInMonth("2028-02-10"))
        assertEquals(28, daysInMonth("2026-02-01"))
        assertEquals(31, daysInMonth("2026-10-31"))
        assertEquals(14, dayOfMonth("2026-10-14"))
        assertEquals("2026-10", monthKey("2026-10-14"))
        assertEquals("October 2026", monthTitle("2026-10"))
        assertEquals("2025-12", addMonthsKey("2026-01", -1))
        assertEquals("2027-01", addMonthsKey("2026-12", 1))
        assertEquals("4 Oct", fmtShort("2026-10-04"))
        assertEquals("Jan", MON[0])
        assertEquals("December", MON_LONG[11])
        assertEquals("1st", ordinal(1))
        assertEquals("2nd", ordinal(2))
        assertEquals("3rd", ordinal(3))
        assertEquals("11th", ordinal(11))
        assertEquals("21th", ordinal(21)) // the web only special-cases 1/2/3
        assertEquals("22th", ordinal(22))
    }
}
