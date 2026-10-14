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
}
