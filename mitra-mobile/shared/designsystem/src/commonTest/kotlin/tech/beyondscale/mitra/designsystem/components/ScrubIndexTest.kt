package tech.beyondscale.mitra.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals

class ScrubIndexTest {
    @Test fun clampsAndMaps() {
        assertEquals(0, scrubIndex(-5f, 160f, 16))
        assertEquals(15, scrubIndex(159.9f, 160f, 16))
        assertEquals(15, scrubIndex(500f, 160f, 16))
        assertEquals(8, scrubIndex(85f, 160f, 16))
        assertEquals(-1, scrubIndex(10f, 160f, 0))
        assertEquals(-1, scrubIndex(10f, 0f, 4))
    }
}
