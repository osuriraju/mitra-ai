package tech.beyondscale.mitra.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals

class LineChartScaleTest {
    private fun near(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)
    private fun near(expected: List<Pair<Float, Float>>, actual: List<Pair<Float, Float>>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e.first, a.first, 0.01f); assertEquals(e.second, a.second, 0.01f) }
    }

    // Web: mx = Math.max(...vals, goal) * 1.08 || 1
    @Test fun goalRaisesMax() {
        near(5.4, chartMax(listOf(1.0, 3.0), 5.0))
        near(8.64, chartMax(listOf(8.0, 3.0), 5.0))
        near(0.54, chartMax(listOf(0.0, 0.5), null))
        near(1.0, chartMax(listOf(0.0, 0.0), null)) // 0 * 1.08 || 1
        near(1.0, chartMax(emptyList(), null))
    }

    // Web: mn = Math.min(...vals) * 0.9 — the goal never lowers the floor.
    @Test fun minIsLowestValueTimesPointNine() {
        near(58.5, chartMin(listOf(70.0, 65.0)))
        near(0.0, chartMin(listOf(0.0, 4.0)))
        near(4.5, chartMin(listOf(5.0, 5.0)))
        near(0.0, chartMin(emptyList()))
    }

    @Test fun weightSeriesWithGoal() {
        // 70 → 65 with goal 65: range 58.5..75.6 (17.1); height 171 makes 1 unit = 10 px.
        val values = listOf(70.0, 65.0)
        val min = chartMin(values); val max = chartMax(values, 65.0)
        near(listOf(0f to 56f, 100f to 106f), chartPoints(values, min, max, width = 100f, height = 171f))
        assertEquals(106f, chartY(65.0, min, max, height = 171f), 0.01f) // goal line sits on the last point
    }

    @Test fun singlePointSitsAtLeftEdge() {
        // [70]: range 63..75.6 (12.6); web n = max(2, len) so x = 0.
        val v = listOf(70.0)
        near(listOf(0f to 56f), chartPoints(v, chartMin(v), chartMax(v, null), width = 100f, height = 126f))
    }

    @Test fun flatAndZeroSeries() {
        // All equal 5: range 4.5..5.4 → 5 sits 5/9 of the way up.
        val flat = listOf(5.0, 5.0)
        near(listOf(0f to 40f, 90f to 40f), chartPoints(flat, chartMin(flat), chartMax(flat, null), width = 90f, height = 90f))
        // All zero: range 0..1 → on the baseline.
        val zero = listOf(0.0, 0.0)
        near(listOf(0f to 50f, 100f to 50f), chartPoints(zero, chartMin(zero), chartMax(zero, null), width = 100f, height = 50f))
        assertEquals(emptyList(), chartPoints(emptyList(), 0.0, 1.0, width = 100f, height = 50f))
        // Degenerate range falls back to a divisor of 1 (web `mx - mn || 1`).
        assertEquals(50f - 0f, chartY(2.0, 2.0, 2.0, height = 50f))
    }

    @Test fun scrubPicksNearestPoint() {
        // 3 points on 300 px sit at 0, 150, 300: x = 90 is closer to the middle one (equal slots would say 0).
        assertEquals(1, nearestPointIndex(90f, 300f, inset = 0f, count = 3))
        assertEquals(0, nearestPointIndex(74f, 300f, inset = 0f, count = 3))
        assertEquals(2, nearestPointIndex(260f, 300f, inset = 0f, count = 3))
        // Out of range clamps; the inset shifts the points (10, 150, 290 on 300 px with a 10 px inset).
        assertEquals(0, nearestPointIndex(-40f, 300f, inset = 10f, count = 3))
        assertEquals(2, nearestPointIndex(999f, 300f, inset = 10f, count = 3))
        assertEquals(1, nearestPointIndex(85f, 300f, inset = 10f, count = 3))
        // A single point (drawn at the left edge) is always the hit; nothing to hit returns -1.
        assertEquals(0, nearestPointIndex(280f, 300f, inset = 0f, count = 1))
        assertEquals(-1, nearestPointIndex(10f, 300f, inset = 0f, count = 0))
        assertEquals(-1, nearestPointIndex(10f, 0f, inset = 0f, count = 3))
    }
}
