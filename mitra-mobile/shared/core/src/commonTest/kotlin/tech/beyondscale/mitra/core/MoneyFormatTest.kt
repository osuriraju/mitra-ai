package tech.beyondscale.mitra.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyFormatTest {
    @Test fun inrPaiseMatchesWebRounding() {
        assertEquals("₹124", inrPaise(12350)) // 123.50 rounds half-up like Math.round
        assertEquals("₹123", inrPaise(12349))
        assertEquals("₹1,500", inrPaise(-150000)) // the web never shows a sign
        assertEquals("₹12,34,568", inrPaise(123456789))
        assertEquals("₹1.50L", inrShortPaise(15000000))
        assertEquals("₹2.5k", inrShortPaise(250000))
        assertEquals("−₹120", signedInrPaise(-12000))
        assertEquals("+₹120", signedInrPaise(12000))
        assertEquals("+₹0", signedInrPaise(0))
    }

    @Test fun jsRoundRoundsHalvesUp() {
        assertEquals(2, jsRound(1.5))
        assertEquals(-1, jsRound(-1.5))
        assertEquals(93, jsRound(0.93 * 100))
    }
}
