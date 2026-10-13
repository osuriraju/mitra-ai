package tech.beyondscale.mitra.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {
    @Test fun inrUsesIndianGrouping() {
        assertEquals("₹0", inr(0.0))
        assertEquals("₹999", inr(999.0))
        assertEquals("₹1,000", inr(1000.0))
        assertEquals("₹1,00,000", inr(100000.0))
        assertEquals("₹12,34,567", inr(1234567.0))
    }

    @Test fun inrRoundsLikeJsMathRoundAndDropsSign() {
        assertEquals("₹1,501", inr(-1500.6))
        assertEquals("₹1,500", inr(-1500.5)) // JS Math.round(-1500.5) = -1500
        assertEquals("₹1,501", inr(1500.5))
    }

    @Test fun inrShortMatchesWeb() {
        assertEquals("₹1.50L", inrShort(150000.0))
        assertEquals("₹2.5k", inrShort(2500.0))
        assertEquals("₹-2.5k", inrShort(-2500.0))
        assertEquals("₹1.2k", inrShort(1234.0))
        assertEquals("₹999", inrShort(999.0))
    }
}
