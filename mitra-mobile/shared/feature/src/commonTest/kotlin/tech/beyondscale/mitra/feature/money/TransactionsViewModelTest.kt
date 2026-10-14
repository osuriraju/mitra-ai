package tech.beyondscale.mitra.feature.money

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.AccountKind
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeMoneyRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModelTest : ViewModelTest() {
    // 04:30Z = 10:00 on Wed 14 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-14T04:30:00Z"), TimeZone.of("Asia/Kolkata"))

    private val hdfc = Account("a1", "HDFC", "••1234", AccountKind.Bank, 1_000_000)
    private val food = Category("food", "Food & dining", "🍜", CategoryKind.Expense)
    private val rent = Category("rent", "Rent", "🏠", CategoryKind.Expense)
    private val salary = Category("salary", "Salary", "💼", CategoryKind.Income)

    private val t1 = Transaction("t1", TxKind.Expense, 45_000, "food", "a1", null, "Swiggy", null, "2026-10-14", "13:05")
    private val t2 = Transaction("t2", TxKind.Expense, 30_000, "food", "a1", null, "Zomato", "dinner", "2026-10-13", "20:00")
    private val t3 = Transaction("t3", TxKind.Expense, 1_500_000, "rent", "a1", null, "Landlord", null, "2026-10-01", "09:00")
    private val t4 = Transaction("t4", TxKind.Income, 10_000_000, "salary", "a1", null, "Beyond Labs", null, "2026-10-01", "10:00")
    private val t5 = Transaction("t5", TxKind.Expense, 20_000, "food", "a1", null, "Swiggy", null, "2026-09-20", "13:00")

    private fun repo() = FakeMoneyRepository(accounts = listOf(hdfc), categories = listOf(food, rent, salary), transactions = listOf(t1, t2, t3, t4, t5))

    private class Harness(val vm: TransactionsViewModel, val events: List<UiEvent<TransactionsAction>>) {
        val state get() = vm.state.value
    }

    private fun TestScope.start(repo: FakeMoneyRepository, initialCategory: String? = null): Harness {
        val vm = TransactionsViewModel(repo, clock)
        val events = mutableListOf<UiEvent<TransactionsAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        vm.start(initialCategory)
        runCurrent()
        return Harness(vm, events)
    }

    @Test fun defaultsToCurrentMonthGroupedWithNet() = runTest {
        val h = start(repo())
        val s = h.state
        assertEquals("2026-10", s.month)
        assertEquals("October 2026", s.monthLabel)
        assertEquals(listOf("All time", "October 2026", "September 2026"), s.monthOptions.map { it.label })
        assertEquals("4 transactions · spent ₹15,750 · income ₹1,00,000", s.summary)
        assertEquals(listOf("Today", "Yesterday", "Earlier"), s.groups.map { it.title })
        assertEquals(listOf("−₹450", "−₹300", "+₹85,000"), s.groups.map { it.net })
        assertFalse(s.showClear)
        assertEquals("Sort by amount", s.sortLabel)
    }

    @Test fun filtersCombineAndSummaryCounts() = runTest {
        val h = start(repo())
        h.vm.onAction(TransactionsAction.Month("2026-10"))
        h.vm.onAction(TransactionsAction.Category("food"))
        h.vm.onAction(TransactionsAction.Query("swi"))
        assertEquals(listOf("t1"), h.state.groups.flatMap { g -> g.rows.map { it.id } })
        assertEquals("1 transaction · spent ₹450 · income ₹0", h.state.summary)
        assertTrue(h.state.showClear)
    }

    @Test fun sortToggleOrdersByAmount() = runTest {
        val h = start(repo())
        h.vm.onAction(TransactionsAction.Month(null))
        h.vm.onAction(TransactionsAction.Kind(TxKind.Expense))
        h.vm.onAction(TransactionsAction.ToggleSort)
        assertEquals("Sort by date", h.state.sortLabel)
        assertEquals(listOf("t1", "t2", "t3", "t5"), h.state.groups.flatMap { g -> g.rows.map { it.id } })
        assertEquals(listOf("Today", "Yesterday", "Earlier"), h.state.groups.map { it.title })
        assertEquals(listOf("t3", "t5"), h.state.groups.last().rows.map { it.id })
    }

    @Test fun deleteThenUndoRestoresSameId() = runTest {
        val r = repo()
        val h = start(r)
        h.vm.onAction(TransactionsAction.Delete("t1"))
        runCurrent()
        assertTrue(r.transactionFlow.value.none { it.id == "t1" })
        val snack = h.events.filterIsInstance<UiEvent.Snack<TransactionsAction>>().single()
        assertEquals("Transaction deleted", snack.text)
        h.vm.onAction(snack.undo!!)
        runCurrent()
        assertTrue("restoreTransaction:t1" in r.calls)
        assertTrue(r.transactionFlow.value.any { it.id == "t1" })
    }

    @Test fun clearFiltersShowsAllTime() = runTest {
        val h = start(repo())
        h.vm.onAction(TransactionsAction.Query("nothing matches"))
        assertTrue(h.state.empty)
        h.vm.onAction(TransactionsAction.ClearFilters)
        assertNull(h.state.month)
        assertEquals("All time", h.state.monthLabel)
        assertEquals("", h.state.query)
        assertFalse(h.state.empty)
        assertEquals("5 transactions · spent ₹15,950 · income ₹1,00,000", h.state.summary)
    }

    @Test fun initialCategoryPresetsFilterOnce() = runTest {
        val h = start(repo(), initialCategory = "rent")
        assertEquals("rent", h.state.categoryId)
        h.vm.onAction(TransactionsAction.Category(null))
        h.vm.start("rent")
        assertNull(h.state.categoryId)
    }

    @Test fun refreshTogglesRefreshing() = runTest {
        val r = repo()
        val h = start(r)
        h.vm.onAction(TransactionsAction.Refresh)
        runCurrent()
        assertTrue("refresh" in r.calls)
        assertFalse(h.state.refreshing)
    }

    @Test fun filtersOnRemovedCategoryOrAccountAreDropped() = runTest {
        val r = repo()
        val h = start(r, initialCategory = "rent")
        h.vm.onAction(TransactionsAction.Account("a1"))
        assertEquals(1, h.state.groups.sumOf { it.rows.size })
        r.categoryFlow.value = listOf(food, salary)
        r.accountFlow.value = emptyList()
        assertNull(h.state.categoryId)
        assertNull(h.state.accountId)
        assertEquals("All categories", h.state.categoryLabel)
        assertFalse(h.state.showClear)
        assertEquals(4, h.state.groups.sumOf { it.rows.size })
    }
}
