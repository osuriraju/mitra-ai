package tech.beyondscale.mitra.domain.money

import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MoneyWidgetModelTest {
    private val today = "2026-10-14"

    private fun model(
        categories: List<Category> = listOf(food(3_000_000), home(null)),
        txs: List<Transaction> = listOf(tx("f", 1_840_000)),
        recurring: List<Recurring> = emptyList(),
        subs: List<Subscription> = emptyList(),
        today: String = this.today,
        signedIn: Boolean = true,
        hideAmounts: Boolean = false,
        moneyEnabled: Boolean = true,
    ) = moneyWidgetModel(categories, txs, recurring, subs, today, signedIn, hideAmounts, moneyEnabled)

    @Test fun budgetLineAndTone() {
        val m = assertIs<MoneyWidgetModel.Budget>(model())
        assertEquals("₹644", m.safe) // (30,000 − 18,400) / 18 = 644.44
        assertEquals("₹18,400 of ₹30,000 · 61%", m.line)
        assertEquals(BudgetTone.Normal, m.tone)
        assertEquals(0.6133f, assertNotNull(m.progress), 0.001f)
        assertNull(m.next)
    }

    @Test fun toneAt90And101Percent() {
        assertEquals(BudgetTone.Warning, assertIs<MoneyWidgetModel.Budget>(model(txs = listOf(tx("f", 2_700_000)))).tone)
        val over = assertIs<MoneyWidgetModel.Budget>(model(txs = listOf(tx("f", 3_030_000))))
        assertEquals(BudgetTone.Danger, over.tone)
        assertEquals(1f, over.progress)
        assertEquals("₹0", over.safe) // negative remaining floors at 0
        assertEquals("₹30,300 of ₹30,000 · 101%", over.line)
    }

    @Test fun zeroRemainingIsZeroPerDay() {
        val m = assertIs<MoneyWidgetModel.Budget>(model(txs = listOf(tx("f", 3_000_000))))
        assertEquals("₹0", m.safe)
        assertEquals(BudgetTone.Warning, m.tone)
        assertEquals(1f, m.progress)
    }

    @Test fun lastDayOfMonthDividesByOne() {
        val m = assertIs<MoneyWidgetModel.Budget>(model(txs = listOf(tx("f", 1_840_000, date = "2026-10-31")), today = "2026-10-31"))
        assertEquals("₹11,600", m.safe)
    }

    @Test fun noBudgetShowsSpent() {
        assertEquals(MoneyWidgetModel.NoBudget("₹18,400"), model(categories = listOf(food(), home())))
        assertEquals(MoneyWidgetModel.NoBudget("₹0"), model(categories = emptyList(), txs = emptyList()))
    }

    @Test fun signedOutShowsNoAmounts() {
        val data = listOf(bill("rent", day = 17))
        assertEquals(MoneyWidgetModel.SignedOut, model(recurring = data, signedIn = false))
        assertEquals(MoneyWidgetModel.SignedOut, model(recurring = data, signedIn = false, moneyEnabled = false))
    }

    @Test fun moneyModuleOffShowsNoAmounts() {
        assertEquals(MoneyWidgetModel.MoneyOff, model(recurring = listOf(bill("rent", day = 17)), moneyEnabled = false))
    }

    @Test fun hiddenAmountsContainNoDigits() {
        val digit = Regex("\\d")
        val m = assertIs<MoneyWidgetModel.Budget>(model(recurring = listOf(bill("rent", day = 17)), hideAmounts = true))
        assertFalse(digit.containsMatchIn(m.safe), m.safe)
        assertFalse(digit.containsMatchIn(m.line), m.line)
        assertEquals(HIDDEN_AMOUNT, m.safe)
        assertEquals("₹•••• of ₹••••", m.line)
        assertNull(m.progress)
        val next = assertNotNull(m.next)
        assertFalse(Regex("₹\\s?\\d").containsMatchIn(next), next)
        assertEquals("🏠 Rent · in 3 days · ₹••••", next)

        val noBudget = assertIs<MoneyWidgetModel.NoBudget>(model(categories = listOf(food()), hideAmounts = true))
        assertFalse(digit.containsMatchIn(noBudget.spent), noBudget.spent)
    }

    @Test fun nextBillLine() {
        val m = assertIs<MoneyWidgetModel.Budget>(model(recurring = listOf(bill("rent", day = 17))))
        assertEquals("🏠 Rent · in 3 days · ₹15,000", m.next)
    }

    @Test fun nextPicksEarliestOfBillsAndRenewals() {
        val m = assertIs<MoneyWidgetModel.Budget>(
            model(recurring = listOf(bill("rent", day = 17)), subs = listOf(sub("nf", next = "2026-10-15"))),
        )
        assertEquals("🔁 Netflix · tomorrow · ₹649", m.next)
        val todayBill = assertIs<MoneyWidgetModel.Budget>(model(recurring = listOf(bill("rent", day = 14))))
        assertEquals("🏠 Rent · today · ₹15,000", todayBill.next)
    }

    @Test fun noUpcomingBills() {
        // Paid this month, and a renewal already in the past: nothing upcoming.
        val m = assertIs<MoneyWidgetModel.Budget>(
            model(recurring = listOf(bill("rent", day = 17, lastPaidOn = "2026-10-02")), subs = listOf(sub("nf", next = "2026-10-01"))),
        )
        assertNull(m.next)
    }
}
