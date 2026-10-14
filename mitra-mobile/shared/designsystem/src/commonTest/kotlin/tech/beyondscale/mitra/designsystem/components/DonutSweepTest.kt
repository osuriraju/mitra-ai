package tech.beyondscale.mitra.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals

class DonutSweepTest {
    @Test fun sweepsSumTo360() {
        assertEquals(listOf(90f, 90f, 180f), donutSweeps(listOf(1, 1, 2)))
        assertEquals(emptyList(), donutSweeps(listOf(0, 0)))
        assertEquals(emptyList(), donutSweeps(emptyList()))
    }
}
