package tech.beyondscale.mitra.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarCellsTest {
    @Test fun octoberStartsThursday() {
        val cells = calendarCells("2026-10-01")
        assertEquals(
            listOf(CalendarCell(null, 28, false), CalendarCell(null, 29, false), CalendarCell(null, 30, false)),
            cells.take(3),
        )
        assertEquals(CalendarCell("2026-10-01", 1, true), cells[3])
        assertEquals(31, cells.count { it.inMonth })
        assertEquals(CalendarCell("2026-10-31", 31, true), cells.last())
    }

    @Test fun mondayMonthHasNoLeadingCells() {
        val cells = calendarCells("2026-06-01")
        assertEquals(CalendarCell("2026-06-01", 1, true), cells.first())
        assertEquals(30, cells.size)
    }

    @Test fun leapFebruaryAndJanuaryWrap() {
        // 2028-02-01 is a Tuesday; Jan has 31 days → one leading cell (31).
        val feb = calendarCells("2028-02-01")
        assertEquals(CalendarCell(null, 31, false), feb.first())
        assertEquals(29, feb.count { it.inMonth })
        // 2027-01-01 is a Friday; previous month is December 2026 → 28..31.
        assertEquals(listOf(28, 29, 30, 31), calendarCells("2027-01-01").takeWhile { !it.inMonth }.map { it.day })
        // Any day of the month is accepted; the grid always starts at the 1st.
        assertEquals(calendarCells("2026-10-01"), calendarCells("2026-10-14"))
    }

    @Test fun cellDescription() {
        val cell = CalendarCell("2026-10-14", 14, true)
        assertEquals("14 October, done", calendarCellDescription(cell, CalendarDayStyle(hit = true, miss = false, today = false, future = false, partial = false)))
        assertEquals("14 October, today, missed", calendarCellDescription(cell, CalendarDayStyle(hit = false, miss = true, today = true, future = false, partial = false)))
        assertEquals("14 October, partial", calendarCellDescription(cell, CalendarDayStyle(hit = false, miss = false, today = false, future = false, partial = true)))
        assertEquals("14 October, upcoming", calendarCellDescription(cell, CalendarDayStyle(hit = false, miss = false, today = false, future = true, partial = false)))
        assertEquals("14 October, not done", calendarCellDescription(cell, CalendarDayStyle(hit = false, miss = false, today = false, future = false, partial = false)))
    }
}
