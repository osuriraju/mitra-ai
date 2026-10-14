package tech.beyondscale.mitra.feature.money

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest : ViewModelTest() {
    private val clock = FixedClock(Instant.parse("2026-10-14T06:00:00Z"))

    private class Harness(val vm: CategoriesViewModel, val events: List<UiEvent<CategoriesAction>>)

    private fun TestScope.start(repo: FakeMoneyRepository): Harness {
        val vm = CategoriesViewModel(repo, clock)
        val events = mutableListOf<UiEvent<CategoriesAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, events)
    }

    @Test fun createExpenseWithBudget() = runTest {
        val repo = FakeMoneyRepository()
        val h = start(repo)
        h.vm.onAction(CategoriesAction.Create("Gifts", "🎁", "2000"))
        runCurrent()
        assertEquals(listOf("addCategory:Gifts"), repo.calls)
        assertEquals(200_000L, repo.categoryFlow.value.single().budgetPaise)
        assertEquals(listOf(UiEvent.Snack<CategoriesAction>("Category created · 🎁 Gifts")), h.events)
    }

    @Test fun blankNameIsIgnored() = runTest {
        val repo = FakeMoneyRepository()
        start(repo).vm.onAction(CategoriesAction.Create("  ", "🎁", ""))
        assertEquals(emptyList(), repo.calls)
    }

    @Test fun incomeTabUsesIncomeTotals() = runTest {
        val repo = FakeMoneyRepository(
            categories = listOf(
                Category("food", "Food", "🍔", CategoryKind.Expense),
                Category("salary", "Salary", "💼", CategoryKind.Income),
            ),
            transactions = listOf(
                Transaction("t1", TxKind.Income, 100_000, "salary", "a1", null, "Co", null, "2026-10-01", "09:00"),
                Transaction("t2", TxKind.Expense, 5_000, "food", "a1", null, "Cafe", null, "2026-10-02", "09:00"),
            ),
        )
        val h = start(repo)
        assertEquals(listOf("₹50"), h.vm.state.value.rows.map { it.value })
        h.vm.onAction(CategoriesAction.SetKind(CategoryKind.Income))
        runCurrent()
        assertEquals(listOf("₹1,000"), h.vm.state.value.rows.map { it.value })
        assertEquals("1 tx", h.vm.state.value.rows.single().subtitle)
        assertEquals("October", h.vm.state.value.monthLabel)
    }

    @Test fun updateAndDelete() = runTest {
        val repo = FakeMoneyRepository(categories = listOf(Category("food", "Food", "🍔", CategoryKind.Expense, budgetPaise = 100_000)))
        val h = start(repo)
        h.vm.onAction(CategoriesAction.Update("food", " Meals ", "1500"))
        h.vm.onAction(CategoriesAction.Delete("food"))
        runCurrent()
        assertEquals(
            listOf("updateCategory:food:Name(v=Meals),Budget(paise=150000)", "deleteCategory:food"),
            repo.calls,
        )
        assertEquals(listOf("Category updated", "Category deleted"), h.events.map { (it as UiEvent.Snack).text })
    }
}
