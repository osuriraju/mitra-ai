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
            "Mon done, Tue missed, Wed done (today), Thu upcoming, Fri upcoming, Sat upcoming, Sun upcoming",
            weekDotsDescription(listOf(true, false, true, false, false, false, false), todayIndex = 2),
        )
        assertEquals("Mon missed, Tue missed (today)", weekDotsDescription(listOf(false, false), todayIndex = 1))
        assertEquals("Mon missed (today)", weekDotsDescription(listOf(false), todayIndex = 0))
    }

    @Test fun ringLabelStopsGrowingPastTheCap() {
        // Web: round(size / 4) px. Font scale may enlarge it by at most 1.25× so "100%" stays inside the ring.
        assertEquals(11f, ringLabelSp(44f, fontScale = 1f), 0.001f)
        assertEquals(11f, ringLabelSp(44f, fontScale = 1.1f), 0.001f) // under the cap: scales with the user's font
        assertEquals(11f * 1.25f / 2f, ringLabelSp(44f, fontScale = 2f), 0.001f) // 200 %: rendered at 13.75 dp, not 22
        assertEquals(10f, ringLabelSp(40f, fontScale = 0.85f), 0.001f) // smaller font scales still shrink normally
    }
}
