package tech.beyondscale.mitra.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals

class LineChartScaleTest {
    @Test fun goalRaisesMax() {
        assertEquals(5.0, chartMax(listOf(1.0, 3.0), 5.0))
        assertEquals(1.0, chartMax(emptyList(), null))
        assertEquals(8.0, chartMax(listOf(8.0, 3.0), 5.0))
        assertEquals(1.0, chartMax(listOf(0.0, 0.5), null))
    }

    @Test fun pointsSpanWidthAndHeight() {
        // Two points over 100 px: x at both edges; value 0 sits on the baseline, the max at the top.
        assertEquals(listOf(0f to 50f, 100f to 0f), chartPoints(listOf(0.0, 4.0), max = 4.0, width = 100f, height = 50f))
        // A single point sits at the left edge (web: n = max(2, len)).
        assertEquals(listOf(0f to 25f), chartPoints(listOf(2.0), max = 4.0, width = 100f, height = 50f))
        assertEquals(emptyList(), chartPoints(emptyList(), max = 1.0, width = 100f, height = 50f))
    }
}
