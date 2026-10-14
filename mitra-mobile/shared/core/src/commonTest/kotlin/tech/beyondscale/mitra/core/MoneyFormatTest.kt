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

    @Test fun localeInMatchesToLocaleString() {
        assertEquals("1,00,000", localeIN(100000.0))
        assertEquals("1,234.568", localeIN(1234.5678))
        assertEquals("12.5", localeIN(12.5))
        assertEquals("0.5", localeIN(0.5))
        assertEquals("62,000", localeIN(62000.0))
        assertEquals("-1,500", localeIN(-1500.0))
        assertEquals("1,00,00,000", localeIN(1e7))
        assertEquals("1", localeIN(0.9999)) // 3 fraction digits, carried into the integer part
        // V8/ICU round the shortest round-trip decimal half-expand, not the exact binary value
        assertEquals("0.001", localeIN(0.0005))
        assertEquals("1.001", localeIN(1.0005)) // exact binary value is 1.000499999…
        assertEquals("1.235", localeIN(1.2345))
        assertEquals("1,00,000.001", localeIN(100000.0005))
        assertEquals("-1.001", localeIN(-1.0005))
        assertEquals("10", localeIN(9.9995))
        assertEquals("-10", localeIN(-9.9995))
        assertEquals("10,00,00,00,00,000", localeIN(1e12))
        assertEquals("0", localeIN(0.0))
        assertEquals("58000", jsNum(58000.0))
        assertEquals("12.5", jsNum(12.5))
        assertEquals("0.1", jsNum(0.1))
        assertEquals("0", jsNum(-0.0))
        assertEquals("-3", jsNum(-3.0))
        assertEquals("0.30000000000000004", jsNum(0.1 + 0.2))
        assertEquals("0.0000125", jsNum(1.25e-5))
        assertEquals("1e-7", jsNum(1e-7))
        assertEquals("1000000000000", jsNum(1e12))
        assertEquals("1e+21", jsNum(1e21))
        assertEquals("100000.0005", jsNum(100000.0005))
    }
}
