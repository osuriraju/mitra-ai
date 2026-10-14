package tech.beyondscale.mitra.domain.tasks

import tech.beyondscale.mitra.domain.tasks.TasksWidgetMetrics.CHROME
import tech.beyondscale.mitra.domain.tasks.TasksWidgetMetrics.FOOTER
import tech.beyondscale.mitra.domain.tasks.TasksWidgetMetrics.ROW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TasksWidgetLayoutTest {
    @Test fun chromeIsPaddingHeaderAndProgressBar() {
        assertEquals(2 * 12 + 32 + 4 + 3 + 6, CHROME)
    }

    @Test fun allRowsWithoutFooterWhenTheyFit() {
        assertEquals(TasksWidgetLayout(rows = 3, more = 0), tasksWidgetLayout(heightDp = 180f, rowCount = 3))
        assertEquals(TasksWidgetLayout(rows = 2, more = 0), tasksWidgetLayout(heightDp = 250f, rowCount = 2))
    }

    @Test fun typicalHeightsReserveRoomForTheFooter() {
        // 4×2 on most launchers is 130–180 dp tall; 4×4 is ~250–330 dp.
        assertEquals(TasksWidgetLayout(rows = 1, more = 0), tasksWidgetLayout(heightDp = 110f, rowCount = 4))
        assertEquals(TasksWidgetLayout(rows = 1, more = 3), tasksWidgetLayout(heightDp = 130f, rowCount = 4))
        assertEquals(TasksWidgetLayout(rows = 2, more = 3), tasksWidgetLayout(heightDp = 180f, rowCount = 5))
        assertEquals(TasksWidgetLayout(rows = 4, more = 3), tasksWidgetLayout(heightDp = 250f, rowCount = 7))
        assertEquals(TasksWidgetLayout(rows = 6, more = 4), tasksWidgetLayout(heightDp = 330f, rowCount = 10))
    }

    @Test fun emptyListHasNoRowsAndNoFooter() {
        assertEquals(TasksWidgetLayout(rows = 0, more = 0), tasksWidgetLayout(heightDp = 250f, rowCount = 0))
    }

    @Test fun tooShortStillShowsOneRowButNoFooter() {
        assertEquals(TasksWidgetLayout(rows = 1, more = 0), tasksWidgetLayout(heightDp = 60f, rowCount = 5))
    }

    @Test fun footerIsNeverClippedAndCountsAddUp() {
        for (h in 60..600) for (n in 0..12) {
            val l = tasksWidgetLayout(h.toFloat(), n)
            val used = CHROME + l.rows * ROW + (if (l.more > 0) FOOTER else 0)
            if (h >= CHROME + ROW) assertTrue(used <= h, "height $h, $n rows: $l uses $used dp")
            assertTrue(l.rows <= n && (n == 0 || l.rows >= 1), "height $h, $n rows: $l")
            if (l.more > 0) assertEquals(n, l.rows + l.more, "height $h, $n rows: $l")
            // A footer is only dropped when even one row plus the footer cannot fit.
            if (l.rows < n && l.more == 0) assertTrue(h < CHROME + ROW + FOOTER, "height $h, $n rows: $l")
        }
    }
}
