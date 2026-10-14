package tech.beyondscale.mitra.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountInputTest {
    private fun type(vararg keys: Char) = keys.fold("") { acc, k -> AmountInput.press(acc, k) }

    @Test
    fun rejectsThirdDecimalAndSecondDot() {
        assertEquals("12.34", type('1', '2', '.', '3', '4', '5'))
        assertEquals("12.3", AmountInput.press("12.3", '.'))
        assertEquals("5", AmountInput.press("0", '5'))
        assertEquals("0.", AmountInput.press("0", '.'))
        assertEquals("0.", AmountInput.press("", '.'))
        assertEquals("12", AmountInput.press("123", '⌫'))
        assertEquals("", AmountInput.backspace(""))
    }

    @Test
    fun liveFormatsIndianGrouping() {
        assertEquals("12,34,567.5", AmountInput.display("1234567.5"))
        assertEquals("0", AmountInput.display(""))
        assertEquals("1,200", AmountInput.display("1200"))
        assertEquals("1,200.", AmountInput.display("1200."))
    }

    @Test
    fun toPaiseIsExact() {
        assertEquals(435L, AmountInput.toPaise("4.35"))
        assertEquals(10L, AmountInput.toPaise("0.1"))
        assertEquals(123410L, AmountInput.toPaise("1234.10"))
        assertEquals(1200L, AmountInput.toPaise("12."))
        assertNull(AmountInput.toPaise("."))
        assertNull(AmountInput.toPaise(""))
        assertNull(AmountInput.toPaise("0"))
    }

    @Test
    fun capsAtServerMaximum() {
        assertEquals("1000000000", AmountInput.press("1000000000", '0'))
        assertEquals("1000000000", AmountInput.press("100000000", '0'))
    }

    @Test
    fun sanitizesPastedRupees() {
        assertEquals("1200.50", AmountInput.sanitize("₹1,200.50"))
    }

    @Test
    fun fromPaiseMatchesWebString() {
        assertEquals("1234.1", AmountInput.fromPaise(123410L))
        assertEquals("1200", AmountInput.fromPaise(120000L))
        assertEquals("4.35", AmountInput.fromPaise(435L))
    }
}
