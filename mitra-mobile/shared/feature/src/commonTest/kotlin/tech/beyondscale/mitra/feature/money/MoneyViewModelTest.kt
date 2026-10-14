package tech.beyondscale.mitra.feature.money

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.AccountKind
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeMoneyRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MoneyViewModelTest : ViewModelTest() {
    private val ist = TimeZone.of("Asia/Kolkata")
    // 04:30Z = 10:00 on Wed 14 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-14T04:30:00Z"), ist)

    private val hdfc = Account("a1", "HDFC", "••4821", AccountKind.Bank, 5_000_000)
    private val icici = Account("a2", "ICICI", "••1234", AccountKind.Credit, -300_000)
    private val food = Category("food", "Food & dining", "🍜", CategoryKind.Expense, budgetPaise = 1_200_000)
    private val rent = Category("rent", "Rent", "🏠", CategoryKind.Expense, budgetPaise = 1_800_000)

    private var seq = 0
    private fun tx(paise: Long, cat: String?, date: String, kind: TxKind = TxKind.Expense, account: String = "a1", to: String? = null, time: String = "12:00") =
        Transaction("t${++seq}", kind, paise, cat, account, to, "", null, date, time)

    /** October: ₹18,400 spent against a ₹30,000 budget, no income; one September expense. */
    private fun fixture() = listOf(
        tx(1_500_000, "rent", "2026-10-01"),
        tx(340_000, "food", "2026-10-12"),
        tx(200_000, "food", "2026-09-20"),
    )

    private class Harness(val vm: MoneyViewModel, val events: List<UiEvent<MoneyAction>>)

    private fun TestScope.start(repo: MoneyRepository, c: FixedClock = clock): Harness {
        val vm = MoneyViewModel(repo, c)
        val events = mutableListOf<UiEvent<MoneyAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, events)
    }

    private fun repo(txs: List<Transaction> = fixture(), categories: List<Category> = listOf(food, rent)) =
        FakeMoneyRepository(accounts = listOf(hdfc, icici), categories = categories, transactions = txs)

    @Test fun summaryKpisForCurrentMonth() = runTest {
        val h = start(repo())
        val s = h.vm.state.value
        assertEquals("October 2026", s.monthTitle)
        assertEquals(false, s.canNext)
        assertEquals(true, s.canPrev)
        val sum = s.summary
        assertEquals("₹18,400", sum.spent)
        assertEquals("of ₹30,000 budget", sum.spentSub)
        assertEquals("₹0", sum.income)
        assertEquals("none yet", sum.incomeSub)
        assertEquals("−₹18,400", sum.saved)
        assertEquals("income − expenses", sum.savedSub)
        assertEquals("61% of budget · 17 days left", sum.budgetFooter)
        // ₹18,400 over 14 days of a 31-day month
        assertEquals("Projected month end: ₹40,743", sum.projected)
        assertEquals(16, sum.daily.size)
        assertEquals(15, sum.dailyHighlight)
        // Sorted by how much of the budget is used: Rent 83 %, then Food 28 %.
        assertEquals(listOf("Rent", "Food & dining"), sum.budgets.map { it.name })
        assertEquals("₹15,000", sum.budgets.first().spent)
        assertEquals("/ ₹18,000", sum.budgets.first().budget)
    }

    @Test fun prevMonthShowsPastMonthFooterWithoutProjection() = runTest {
        val h = start(repo())
        h.vm.onAction(MoneyAction.PrevMonth)
        val s = h.vm.state.value
        assertEquals("September 2026", s.monthTitle)
        assertTrue(s.summary.budgetFooter.endsWith(" · September 2026"), s.summary.budgetFooter)
        assertNull(s.summary.projected)
        assertEquals(true, s.canNext)
        assertEquals(false, s.canPrev)
        assertEquals("₹2,000", s.summary.spent)
        assertEquals(30, s.summary.daily.size)
        assertEquals(-1, s.summary.dailyHighlight)
        // Prev is bounded by the earliest month with data.
        h.vm.onAction(MoneyAction.PrevMonth)
        assertEquals("September 2026", h.vm.state.value.monthTitle)
    }

    @Test fun nextDisabledAtCurrentMonth() = runTest {
        val h = start(repo())
        h.vm.onAction(MoneyAction.NextMonth)
        assertEquals("October 2026", h.vm.state.value.monthTitle)
        assertEquals("2026-10", h.vm.state.value.month)
    }

    @Test fun accountFilterIncludesIncomingTransfers() = runTest {
        val transfer = tx(1_000_000, null, "2026-10-13", kind = TxKind.Transfer, account = "a1", to = "a2")
        val h = start(repo(fixture() + transfer))
        assertEquals(listOf("All accounts", "HDFC ••4821", "ICICI ••1234"), h.vm.state.value.accountFilters.map { it.label })
        h.vm.onAction(MoneyAction.SelectAccount("a2"))
        val s = h.vm.state.value
        assertEquals("a2", s.selectedAccount)
        assertEquals(listOf(transfer.id), s.summary.recent.map { it.id })
        assertEquals("₹0", s.summary.spent)
        assertEquals("0% of budget · 17 days left", s.summary.budgetFooter)
        h.vm.onAction(MoneyAction.SelectAccount(null))
        assertEquals("₹18,400", h.vm.state.value.summary.spent)
        assertTrue(h.vm.state.value.summary.recent.any { it.id == transfer.id })
    }

    @Test fun analyticsFoldsNinthCategoryIntoOther() = runTest {
        val cats = (1..9).map { Category("c$it", "Cat $it", "🏷", CategoryKind.Expense) }
        // Cat 1 spends the most, Cat 9 the least.
        val txs = (1..9).map { tx((10 - it) * 10_000L, "c$it", "2026-10-0${it}") }
        val h = start(repo(txs, cats))
        h.vm.onAction(MoneyAction.SelectTab(1))
        val a = h.vm.state.value.analytics
        assertEquals(1, h.vm.state.value.tab)
        assertEquals(9, a.donut.size)
        assertEquals((0..7).toList(), a.donut.take(8).map { it.colorIndex })
        assertEquals("Other (1)", a.donut.last().label)
        assertNull(a.donut.last().colorIndex)
        assertEquals(9, a.ranking.size)
        assertEquals("Cat 1", a.ranking.first().name)
        // ₹900 of ₹4,500
        assertEquals("20.0%", a.ranking.first().share)
        assertEquals("₹4,500", a.totalExpenses)
        assertEquals("9 debits & charges", a.expensesSub)
        assertEquals("—", a.savingsRate)
        assertEquals(5, a.topOutflows.size)
        assertEquals(false, a.empty)
    }

    @Test fun analyticsEmptyMonth() = runTest {
        val h = start(repo(emptyList()))
        val a = h.vm.state.value.analytics
        assertTrue(a.empty)
        assertEquals("No transactions in October 2026", a.emptyTitle)
        assertEquals("Nothing spent in October 2026 yet.", h.vm.state.value.summary.cashFlowEmpty)
    }

    @Test fun monthRollsOverAtMidnight() = runTest {
        val c = FixedClock(Instant.parse("2026-10-31T18:29:00Z"), ist) // 23:59 IST on 31 Oct
        val h = start(repo(), c)
        assertEquals("October 2026", h.vm.state.value.monthTitle)
        assertEquals("₹18,400", h.vm.state.value.summary.spent)
        c.instant = Instant.parse("2026-10-31T18:30:30Z") // 00:00:30 IST on 1 Nov
        advanceTimeBy(60_002)
        val s = h.vm.state.value
        assertEquals("November 2026", s.monthTitle)
        assertEquals("₹0", s.summary.spent)
        assertEquals(false, s.canNext)
    }

    @Test fun refreshReportsOfflineButNotSuperseded() = runTest {
        val r = repo()
        val h = start(r)
        r.refreshResult = Outcome.Err(AppError.Server(409, MoneyRepository.REFRESH_SUPERSEDED, "superseded"))
        h.vm.onAction(MoneyAction.Refresh)
        assertEquals(emptyList(), h.events)
        r.refreshResult = Outcome.Err(AppError.Offline)
        h.vm.onAction(MoneyAction.Refresh)
        assertEquals(1, h.events.size)
        assertEquals(listOf("refresh", "refresh"), r.calls)
        assertEquals(false, h.vm.state.value.refreshing)
    }
}
