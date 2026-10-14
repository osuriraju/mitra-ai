package tech.beyondscale.mitra.domain.money

import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.domain.model.TxKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneySelectorsTest {
    @Test fun spentIgnoresIncomeAndTransfers() {
        val txs = listOf(
            tx("e", 45_000),
            tx("i", 100_000, kind = TxKind.Income, categoryId = "salary"),
            tx("t", 20_000, kind = TxKind.Transfer, categoryId = null, toAccountId = "hdfc"),
        )
        assertEquals(45_000, spent(txs))
        assertEquals(100_000, income(txs))
        assertEquals(20_000, transferred(txs))
    }

    @Test fun spentByCategoryKeysUncategorised() {
        assertEquals(mapOf("uncategorised" to 5_000L), spentByCategory(listOf(tx("a", 5_000, categoryId = null))))
        assertEquals(UNCATEGORISED, "uncategorised")
    }

    @Test fun totalBudgetSkipsIncomeAndNull() {
        assertEquals(600_000, totalBudget(listOf(food(600_000), salary(100_000), transport(null))))
        assertEquals(0, totalBudget(listOf(food(600_000).copy(archived = true))))
    }

    @Test fun budgetToneThresholds() {
        assertEquals(BudgetTone.Normal, budgetTone(89, 100))
        assertEquals(BudgetTone.Warning, budgetTone(90, 100))
        assertEquals(BudgetTone.Warning, budgetTone(100, 100))
        assertEquals(BudgetTone.Danger, budgetTone(101, 100))
        assertEquals(93, budgetPct(93, 100))
        assertEquals(0, budgetPct(50, 0))
    }

    @Test fun safeToSpendOnLastDayDividesByOne() {
        val m = todayMoney(listOf(food(3_000_000)), listOf(tx("a", 2_900_000, date = "2026-10-02")), emptyList(), emptyList(), "2026-10-31")
        assertEquals(1, m.daysLeft)
        assertEquals(1000.0, m.safePerDayRupees)
        assertEquals("1 day left in Oct", daysLeftLabel(m))
    }

    @Test fun firstOfMonthStartsAFreshMonth() {
        val txs = listOf(tx("oct", 900_000, date = "2026-10-31"), tx("nov", 10_000, date = "2026-11-01"))
        val m = todayMoney(listOf(food(3_000_000)), txs, emptyList(), emptyList(), "2026-11-01")
        assertEquals(10_000, m.spentPaise)
        assertEquals(30, m.daysLeft)
        val safe = assertNotNull(m.safePerDayRupees)
        assertTrue(abs(safe - 996.67) <= 0.01, "safe = $safe")
        assertEquals("30 days left in Nov", daysLeftLabel(m))
        assertEquals(10_000, spentToday(txs, "2026-11-01"))
    }

    @Test fun overspentFloorsAtZero() {
        val m = todayMoney(listOf(food(100_000)), listOf(tx("a", 150_000)), emptyList(), emptyList(), "2026-10-14")
        assertEquals(0.0, m.safePerDayRupees)
        assertEquals("Over budget", remainingTitle(m))
        assertEquals(-50_000, m.remainingPaise)
        assertEquals("₹500", inrPaise(abs(m.remainingPaise)))
        assertEquals("₹0", safeLabel(m))
    }

    @Test fun nextBillWrapsAcrossMonthEnd() {
        val today = "2026-10-30"
        assertEquals(3, nextBill(listOf(bill("r", day = 2)), emptyList(), today)?.days) // (2 − 30 + 31) % 31
        val due = assertNotNull(nextBill(listOf(bill("r", day = 30)), emptyList(), today))
        assertEquals(0, due.days)
        assertEquals("today", nextLabel(due))
        assertNull(nextBill(listOf(bill("r", day = 5, lastPaidOn = "2026-10-05")), emptyList(), today))
        val n = assertNotNull(nextBill(listOf(bill("r", day = 2)), listOf(sub("s", next = "2026-10-31")), today))
        assertEquals(NextBill("🔁", "Netflix", 64_900, 1), n)
        assertEquals("tomorrow", nextLabel(n))
        assertEquals("in 3 days", nextLabel(n.copy(days = 3)))
    }

    @Test fun groupsTodayYesterdayEarlierUpcoming() {
        val list = listOf(
            tx("t1", 45_000, date = "2026-10-14"),
            tx("t2", 100_000, date = "2026-10-14", kind = TxKind.Income, categoryId = "salary"),
            tx("y", 1_000, date = "2026-10-13"),
            tx("e", 2_000, date = "2026-10-10"),
            tx("u", 3_000, date = "2026-10-20"),
        )
        val groups = groupTransactions(list, "2026-10-14")
        assertEquals(listOf("Today", "Yesterday", "Earlier", "Upcoming"), groups.map { it.title })
        assertEquals(55_000, groups[0].netPaise)
        assertEquals(listOf("t1", "t2"), groups[0].items.map { it.id })
        assertEquals(-1_000, groups[1].netPaise)
        assertEquals(listOf("Today"), groupTransactions(list.take(1), "2026-10-14").map { it.title })
    }

    @Test fun filterBySearchMatchesMerchantOrNote() {
        val txs = listOf(
            tx("a", 45_000, merchant = "Swiggy", date = "2026-10-14", time = "20:00"),
            tx("b", 250_000, merchant = "Tanishq", note = "Mom's birthday", date = "2026-10-12"),
            tx("c", 9_000, merchant = "Metro", categoryId = "transport", date = "2026-09-30"),
        )
        assertEquals(listOf("a"), filterTransactions(txs, TxFilter(query = "swig"), TxSort.Date).map { it.id })
        assertEquals(listOf("b"), filterTransactions(txs, TxFilter(query = "birthday"), TxSort.Date).map { it.id })
        assertEquals(listOf("b", "a", "c"), filterTransactions(txs, TxFilter(), TxSort.Amount).map { it.id })
        assertEquals(listOf("a", "b", "c"), filterTransactions(txs, TxFilter(), TxSort.Date).map { it.id })
        assertEquals(listOf("a", "b"), filterTransactions(txs, TxFilter(month = "2026-10"), TxSort.Date).map { it.id })
        assertEquals(listOf("c"), filterTransactions(txs, TxFilter(categoryId = "transport"), TxSort.Date).map { it.id })
    }

    @Test fun dailySpendCurrentMonthIsLast16Days() {
        val pts = dailySpend(listOf(tx("a", 45_000, date = "2026-10-14"), tx("b", 5_000, date = "2026-10-14")), "2026-10", "2026-10-14")
        assertEquals(16, pts.size)
        assertEquals("2026-09-29", pts.first().date)
        assertEquals("2026-10-14", pts.last().date)
        assertEquals(50_000, pts.last().paise)
        assertEquals(listOf(0, 3, 6, 9, 12, 15), pts.indices.filter { pts[it].label.isNotEmpty() })
        assertEquals("29", pts[0].label)
        assertEquals("14", pts[15].label)
        val past = dailySpend(emptyList(), "2026-09", "2026-10-14")
        assertEquals(30, past.size)
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 29), past.indices.filter { past[it].label.isNotEmpty() })
    }

    @Test fun findDuplicateIsCaseInsensitive() {
        val txs = listOf(tx("a", 45_000, merchant = "Swiggy", date = "2026-10-14"))
        assertNotNull(findDuplicate(txs, " swiggy ", 45_000, "2026-10-14"))
        assertNull(findDuplicate(txs, "swiggy", 46_000, "2026-10-14"))
        assertNull(findDuplicate(txs, "  ", 45_000, "2026-10-14"))
    }

    @Test fun summaryHelpersMatchTheWeb() {
        val today = "2026-10-14"
        val txs = listOf(
            tx("a", 45_000, merchant = "Swiggy", date = "2026-10-14", time = "20:00"),
            tx("b", 30_000, merchant = "Zomato", date = "2026-10-14", time = "13:00"),
            tx("c", 9_000, merchant = "Metro", categoryId = "transport", date = "2026-08-30"),
            tx("d", 2_000, merchant = "Chai", categoryId = null, date = "2026-10-01"),
        )
        assertEquals(listOf("2026-10", "2026-08"), monthsWithData(txs, today))
        assertEquals(listOf("a", "b"), recentTransactions(txs, 2).map { it.id })
        // ₹770 over 14 days → ₹55/day → ₹1,705 in 31 days, rounded to whole rupees like the web
        assertEquals(170_500, projectedMonthEnd(77_000, "2026-10", today))
        val cats = categoryBreakdown(txs, listOf(food(), transport()))
        assertEquals(listOf(CatSpend("food", "Food & dining", "🍛", 75_000, 2), CatSpend("transport", "Transport", "🚇", 9_000, 1), CatSpend(UNCATEGORISED, "Uncategorised", "💸", 2_000, 1)), cats)
        assertEquals(listOf("Swiggy", "Zomato"), recentMerchants(txs, "food"))
        assertEquals(12.5, savingsRatePct(100_000, 87_500))
        assertNull(savingsRatePct(0, 87_500))
        assertEquals(listOf("a", "b"), topOutflows(txs, 2).map { it.id })
    }

    @Test fun recurringHelpers() {
        val today = "2026-10-30"
        val rec = listOf(bill("rent", day = 2), bill("ott", day = 30, paise = 50_000, lastPaidOn = "2026-10-30"), bill("gym", day = 20, paise = 200_000))
        val subs = listOf(sub("n", next = "2026-11-05", lastUsed = "2026-09-01"), sub("y", next = "2027-01-01", paise = 120_000, cycle = tech.beyondscale.mitra.domain.model.Cycle.Yearly))
        assertEquals(3, daysToDay(2, today))
        assertEquals(listOf("ott", "rent"), dueSoon(rec, today).map { it.id })
        assertTrue(paidThisMonth(rec[1], today))
        assertEquals(1_500_000 + 50_000 + 200_000 + 64_900, recurringMonthTotal(rec, subs))
        assertEquals(64_900 + 10_000.0, subscriptionsMonthlyPaise(subs))
        assertEquals(listOf("n"), unusedSubscriptions(subs, today).map { it.id })
        assertEquals(listOf("n"), renewalsWithin30(subs, today).map { it.id })
        assertEquals(listOf("ott", "rent", "n"), upcoming(rec, subs, today).map { it.id })
    }
}
