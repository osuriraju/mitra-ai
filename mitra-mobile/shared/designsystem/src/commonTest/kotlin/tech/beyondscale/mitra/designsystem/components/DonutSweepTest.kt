package tech.beyondscale.mitra.designsystem.components

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class DonutSweepTest {
    @Test fun sweepsSumTo360() {
        assertEquals(listOf(90f, 90f, 180f), donutSweeps(listOf(1, 1, 2)))
        assertEquals(emptyList(), donutSweeps(listOf(0, 0)))
        assertEquals(emptyList(), donutSweeps(emptyList()))
    }

    @Test fun summaryListsLabelsAndPercentages() {
        val segs = listOf(DonutSegment(4, Color.Red, "Food"), DonutSegment(3, Color.Red, "Rent"), DonutSegment(3, Color.Red, "Other"))
        assertEquals("Food 40%, Rent 30%, Other 30%, ₹10", donutSummary(segs, "₹10"))
        assertEquals("", donutSummary(emptyList()))
        assertEquals("₹0", donutSummary(listOf(DonutSegment(0, Color.Red, "A")), "₹0"))
    }
}
