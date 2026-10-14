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
class BudgetsViewModelTest : ViewModelTest() {
    private fun clock(date: String) = FixedClock(Instant.parse("${date}T06:00:00Z"))

    private fun cat(id: String, kind: CategoryKind = CategoryKind.Expense, budget: Long? = null, slug: String? = null) =
        Category(id, id.replaceFirstChar { it.uppercase() }, "🍔", kind, slug, budget)

    private fun tx(cat: String, paise: Long, date: String = "2026-10-10") =
        Transaction("t-$cat-$paise", TxKind.Expense, paise, cat, "a1", null, "m", null, date, "10:00")

    private class Harness(val vm: BudgetsViewModel, val events: List<UiEvent<BudgetsAction>>)

    private fun TestScope.start(repo: FakeMoneyRepository, date: String = "2026-10-14"): Harness {
        val vm = BudgetsViewModel(repo, clock(date))
        val events = mutableListOf<UiEvent<BudgetsAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, events)
    }

    @Test fun headerOnLastDay() = runTest {
        val repo = FakeMoneyRepository(
            categories = listOf(cat("food", budget = 3_000_000)),
            transactions = listOf(tx("food", 2_900_000)),
        )
        val s = start(repo, "2026-10-31").vm.state.value
        assertEquals("₹30,000", s.total)
        assertEquals("₹29,000", s.spent)
        assertEquals("₹1,000", s.left)
        assertEquals("1 days", s.leftSub)
        assertEquals("Safe to spend: ₹1,000 / day until 31 Oct", s.safeLine)
    }

    @Test fun chipStates() = runTest {
        val repo = FakeMoneyRepository(
            categories = listOf(
                cat("food", budget = 100_000), cat("transport", budget = 100_000), cat("health", budget = 100_000),
            ),
            transactions = listOf(tx("food", 101_000), tx("transport", 90_000), tx("health", 50_000)),
        )
        val cards = start(repo).vm.state.value.cards.associateBy { it.id }
        assertEquals("Over by ₹10", cards.getValue("food").chip)
        assertEquals("Near limit", cards.getValue("transport").chip)
        assertEquals("On track", cards.getValue("health").chip)
        assertEquals("—", cards.getValue("food").leftText)
        assertEquals("₹500 left", cards.getValue("health").leftText)
        assertEquals("₹500 of ₹1,000", cards.getValue("health").amounts)
        assertEquals("Monthly · resets 1 Nov", cards.getValue("health").resets)
    }

    @Test fun removeSendsNullBudget() = runTest {
        val repo = FakeMoneyRepository(categories = listOf(cat("food", budget = 100_000)))
        val h = start(repo)
        h.vm.onAction(BudgetsAction.Remove("food"))
        runCurrent()
        assertEquals(listOf("updateCategory:food:Budget(paise=null)"), repo.calls)
        assertEquals(listOf(UiEvent.Snack<BudgetsAction>("Budget removed")), h.events)
    }

    @Test fun saveAndAddConvertRupeesToPaise() = runTest {
        val repo = FakeMoneyRepository(categories = listOf(cat("food", budget = 100_000), cat("fun")))
        val h = start(repo)
        h.vm.onAction(BudgetsAction.Save("food", "2000"))
        h.vm.onAction(BudgetsAction.Add("fun", "500"))
        runCurrent()
        assertEquals(
            listOf("updateCategory:food:Budget(paise=200000)", "updateCategory:fun:Budget(paise=50000)"),
            repo.calls,
        )
        assertEquals(listOf("Budget updated", "Budget set"), h.events.map { (it as UiEvent.Snack).text })
    }

    @Test fun unbudgetedExcludesTransferAndIncome() = runTest {
        val repo = FakeMoneyRepository(
            categories = listOf(
                cat("food", budget = 100_000), cat("fun"), cat("transfer", slug = "transfer"),
                cat("salary", CategoryKind.Income),
            ),
        )
        assertEquals(listOf("fun"), start(repo).vm.state.value.unbudgeted.map { it.id })
    }
}
