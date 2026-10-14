package tech.beyondscale.mitra.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals

class RingSweepTest {
    @Test fun clamps() {
        assertEquals(0f, ringSweep(-5))
        assertEquals(90f, ringSweep(25))
        assertEquals(360f, ringSweep(140))
        assertEquals(0f, ringSweep(0))
        assertEquals(360f, ringSweep(100))
    }

    @Test fun heatLevelsStepTheAccent() {
        assertEquals(listOf(0f, 0.30f, 0.55f, 0.80f, 1f), (0..4).map { heatFraction(it) })
        assertEquals(0f, heatFraction(-1))
        assertEquals(1f, heatFraction(9))
    }

    @Test fun weekDotsSpeakEachDay() {
        assertEquals(
            "Mon done, Tue missed, Wed done (today), Thu missed, Fri missed, Sat missed, Sun missed",
            weekDotsDescription(listOf(true, false, true, false, false, false, false), todayIndex = 2),
        )
    }
}
