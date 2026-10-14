package tech.beyondscale.mitra.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeOutcomeTest {
    @Test fun resolvesDirectionPastThreshold() {
        assertEquals(SwipeOutcome.None, swipeOutcome(0f, 1000f))
        assertEquals(SwipeOutcome.None, swipeOutcome(340f, 1000f))
        assertEquals(SwipeOutcome.Right, swipeOutcome(351f, 1000f))
        assertEquals(SwipeOutcome.Left, swipeOutcome(-351f, 1000f))
        assertEquals(SwipeOutcome.None, swipeOutcome(10f, 0f))
    }

    @Test fun clampsDragToAllowedDirections() {
        assertEquals(0f, clampDrag(-50f, canRight = true, canLeft = false, widthPx = 1000f))
        assertEquals(0f, clampDrag(50f, canRight = false, canLeft = true, widthPx = 1000f))
        assertEquals(50f, clampDrag(50f, canRight = true, canLeft = true, widthPx = 1000f))
        assertEquals(1000f, clampDrag(1500f, canRight = true, canLeft = true, widthPx = 1000f))
        assertEquals(0f, clampDrag(30f, canRight = false, canLeft = false, widthPx = 1000f))
    }
}
