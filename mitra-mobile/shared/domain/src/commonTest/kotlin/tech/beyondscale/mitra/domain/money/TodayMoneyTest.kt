package tech.beyondscale.mitra.domain.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TodayMoneyTest {
    private val today = "2026-10-14"

    @Test fun alertPrefersOverOverNear() {
        val cats = listOf(food(1_000_000), transport(200_000))
        val over = todayMoney(cats, listOf(tx("f", 1_100_000), tx("t", 190_000, categoryId = "transport")), emptyList(), emptyList(), today)
        assertEquals(BudgetAlert("🍛", "Food & dining", over = true, text = "🍛 Food & dining over by ₹1,000"), over.alert)

        val near = todayMoney(cats, listOf(tx("f", 500_000), tx("t", 190_000, categoryId = "transport")), emptyList(), emptyList(), today)
        assertEquals(BudgetAlert("🚇", "Transport", over = false, text = "🚇 Transport at 95% of its budget"), near.alert)

        assertNull(todayMoney(cats, listOf(tx("f", 500_000)), emptyList(), emptyList(), today).alert)
    }

    @Test fun summaryNumbersAndLabels() {
        val m = todayMoney(
            listOf(food(3_000_000), home(null)), listOf(tx("f", 1_016_000), tx("old", 900_000, date = "2026-09-30")),
            listOf(bill("rent", day = 17)), emptyList(), today,
        )
        assertEquals(3_000_000, m.budgetPaise)
        assertEquals(1_016_000, m.spentPaise)
        assertEquals(1_984_000, m.remainingPaise)
        assertEquals(18, m.daysLeft)
        assertEquals("Oct", m.monthShort)
        assertEquals("₹1,102", safeLabel(m)) // 19,840 / 18 = 1,102.2
        assertEquals("18 days left in Oct", daysLeftLabel(m))
        assertEquals("Left this month", remainingTitle(m))
        val next = assertNotNull(m.next)
        assertEquals(NextBill("🏠", "Rent", 1_500_000, 3), next)
        assertEquals("in 3 days", nextLabel(next))
    }

    @Test fun noBudgetShowsDash() {
        val m = todayMoney(listOf(food(), transport()), listOf(tx("f", 45_000)), emptyList(), emptyList(), today)
        assertNull(m.safePerDayRupees)
        assertEquals("—", safeLabel(m))
        assertEquals("Set budgets to see this", daysLeftLabel(m))
        assertEquals("Over budget", remainingTitle(m)) // the web: 0 − spent < 0
    }
}
