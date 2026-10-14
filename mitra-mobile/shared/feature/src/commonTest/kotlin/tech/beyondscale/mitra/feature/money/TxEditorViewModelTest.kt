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
import tech.beyondscale.mitra.domain.model.RecurringKind
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
class TxEditorViewModelTest : ViewModelTest() {
    // 08:00Z = 13:30 on Wed 14 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-14T08:00:00Z"), TimeZone.of("Asia/Kolkata"))

    private val hdfc = Account("a1", "HDFC", "••1234", AccountKind.Bank, 1_240_000)
    private val icici = Account("a2", "ICICI", "••9876", AccountKind.Credit, -300_000)
    private val food = Category("food", "Food & dining", "🍜", CategoryKind.Expense, slug = "food")
    private val rent = Category("rent", "Rent", "🏠", CategoryKind.Expense, slug = "rent")
    private val transfer = Category("xfer", "Transfer", "🔁", CategoryKind.Expense, slug = "transfer")
    private val salary = Category("salary", "Salary", "💼", CategoryKind.Income, slug = "salary")

    private fun repo(
        categories: List<Category> = listOf(food, rent, transfer, salary),
        accounts: List<Account> = listOf(hdfc, icici),
        txs: List<Transaction> = emptyList(),
    ) = FakeMoneyRepository(accounts = accounts, categories = categories, transactions = txs)

    private class Harness(val vm: TxEditorViewModel, val events: List<UiEvent<TxEditorAction>>) {
        fun type(s: String) = s.forEach { vm.onAction(TxEditorAction.Key(it)) }
        val state get() = vm.state.value
        val snacks get() = events.filterIsInstance<UiEvent.Snack<TxEditorAction>>().map { it.text }
    }

    private fun TestScope.start(repo: FakeMoneyRepository, editId: String? = null): Harness {
        val vm = TxEditorViewModel(repo, clock)
        val events = mutableListOf<UiEvent<TxEditorAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        vm.start(editId)
        runCurrent()
        return Harness(vm, events)
    }

    @Test fun keypadBuildsAmountAndFormatsLive() = runTest {
        val r = repo()
        val h = start(r)
        h.type("12345.5")
        assertEquals("12,345.5", h.state.amountDisplay)
        assertEquals("Add expense", h.state.title)
        assertEquals("food", h.state.categoryId)
        assertEquals("a1", h.state.accountId)
        assertEquals("13:30", h.state.time)
        assertEquals("2026-10-14", h.state.date)
        h.vm.onAction(TxEditorAction.Save(again = false))
        runCurrent()
        val saved = r.transactionFlow.value.single()
        assertEquals(1_234_550L, saved.amountPaise)
        assertEquals(listOf("Expense saved · ₹12,346"), h.snacks)
        assertTrue(h.events.last() is UiEvent.Close)
    }

    @Test fun validationOrder() = runTest {
        val h = start(repo(categories = emptyList()))
        h.vm.onAction(TxEditorAction.Save(false))
        assertEquals("Enter an amount", h.state.error)
        h.type("50")
        assertNull(h.state.error)
        h.vm.onAction(TxEditorAction.Save(false))
        assertEquals("Pick a category", h.state.error)

        val t = start(repo(accounts = listOf(hdfc, icici)))
        t.type("50")
        t.vm.onAction(TxEditorAction.Kind(TxKind.Transfer))
        t.vm.onAction(TxEditorAction.ToAccount("a1"))
        t.vm.onAction(TxEditorAction.Save(false))
        assertEquals("Pick a different account to transfer to", t.state.error)

        val none = start(repo(accounts = emptyList()))
        none.type("50")
        none.vm.onAction(TxEditorAction.Save(false))
        assertEquals("Pick an account", none.state.error)
        runCurrent()
        assertTrue(none.events.none { it is UiEvent.Close })
    }

    @Test fun saveWithRepeatAddsRecurring() = runTest {
        val r = repo()
        val h = start(r)
        h.vm.onAction(TxEditorAction.Category("rent"))
        h.type("15000")
        h.vm.onAction(TxEditorAction.Date("2026-10-30"))
        h.vm.onAction(TxEditorAction.Merchant("Rent"))
        h.vm.onAction(TxEditorAction.ToggleRepeat)
        assertEquals("Repeats monthly ✓", h.state.repeatLabel)
        h.vm.onAction(TxEditorAction.Save(false))
        runCurrent()
        val rec = r.recurringFlow.value.single()
        assertEquals("Rent", rec.name)
        assertEquals(28, rec.day)
        assertEquals(RecurringKind.Bill, rec.kind)
        assertEquals(1_500_000L, rec.amountPaise)
        assertEquals("🏠", rec.emoji)
        assertEquals("rent", rec.categoryId)
        assertEquals("a1", rec.accountId)
        assertNull(rec.toAccountId)
        assertEquals(listOf("Expense saved · ₹15,000 · repeats monthly"), h.snacks)
    }

    @Test fun saveAndAddAnotherClearsFields() = runTest {
        val r = repo()
        val h = start(r)
        h.vm.onAction(TxEditorAction.Category("rent"))
        h.type("450")
        h.vm.onAction(TxEditorAction.Merchant("Swiggy"))
        h.vm.onAction(TxEditorAction.Note("lunch"))
        h.vm.onAction(TxEditorAction.Save(again = true))
        runCurrent()
        assertEquals(1, r.transactionFlow.value.size)
        assertTrue(h.events.none { it is UiEvent.Close })
        assertEquals("", h.state.amountText)
        assertEquals("", h.state.merchant)
        assertEquals("", h.state.note)
        assertEquals("rent", h.state.categoryId)
        assertFalse(h.state.saving)
        // The guard is released: a second entry saves again.
        h.type("20")
        h.vm.onAction(TxEditorAction.Save(again = true))
        runCurrent()
        assertEquals(2, r.transactionFlow.value.size)
    }

    @Test fun saveIsGuardedAgainstDoubleTap() = runTest {
        val r = repo()
        val h = start(r)
        h.type("450")
        h.vm.onAction(TxEditorAction.Save(false))
        h.vm.onAction(TxEditorAction.Save(false))
        runCurrent()
        assertEquals(1, r.calls.count { it == "addTransaction" })
    }

    @Test fun undoAddDeletesTheSavedTransaction() = runTest {
        val r = repo()
        val h = start(r)
        h.type("450")
        h.vm.onAction(TxEditorAction.Save(false))
        runCurrent()
        val undo = h.events.filterIsInstance<UiEvent.Snack<TxEditorAction>>().single().undo
        assertEquals(TxEditorAction.UndoAdd("tx1"), undo)
        h.vm.onAction(undo!!)
        runCurrent()
        assertTrue(r.transactionFlow.value.isEmpty())
    }

    @Test fun editPrefillsAndSendsUpdate() = runTest {
        val tx = Transaction("t1", TxKind.Expense, 123_410, "food", "a2", null, "Swiggy", "dinner", "2026-10-12", "20:15")
        val r = repo(txs = listOf(tx))
        val h = start(r, editId = "t1")
        val s = h.state
        assertEquals("1234.1", s.amountText)
        assertEquals("Edit transaction", s.title)
        assertEquals("Save changes", s.saveLabel)
        assertFalse(s.showKeypad)
        assertFalse(s.showRepeat)
        assertTrue(s.showDelete)
        assertEquals("food", s.categoryId)
        assertEquals("a2", s.accountId)
        assertEquals("Swiggy", s.merchant)
        assertEquals("dinner", s.note)
        assertEquals("2026-10-12", s.date)
        assertEquals("20:15", s.time)
        h.vm.onAction(TxEditorAction.Merchant("Zomato"))
        h.vm.onAction(TxEditorAction.Save(false))
        runCurrent()
        assertTrue("updateTransaction:t1" in r.calls)
        assertEquals("Zomato", r.transactionFlow.value.single().merchant)
        assertEquals(123_410L, r.transactionFlow.value.single().amountPaise)
        assertEquals(listOf("Transaction updated"), h.snacks)
        assertTrue(h.events.last() is UiEvent.Close)
    }

    @Test fun deleteFromEdit() = runTest {
        val tx = Transaction("t1", TxKind.Expense, 45_000, "food", "a1", null, "Swiggy", null, "2026-10-12", "20:15")
        val r = repo(txs = listOf(tx))
        val h = start(r, editId = "t1")
        h.vm.onAction(TxEditorAction.Delete)
        runCurrent()
        assertTrue(r.transactionFlow.value.isEmpty())
        assertEquals(listOf("Transaction deleted"), h.snacks)
        assertTrue(h.events.last() is UiEvent.Close)
    }

    @Test fun duplicateWarning() = runTest {
        val existing = Transaction("t1", TxKind.Expense, 45_000, "food", "a1", null, "Swiggy", null, "2026-10-14", "13:05")
        val h = start(repo(txs = listOf(existing)))
        h.type("450")
        assertNull(h.state.duplicate)
        h.vm.onAction(TxEditorAction.Merchant("swiggy"))
        assertEquals("Looks like Swiggy ₹450 was already added at 1:05 PM.", h.state.duplicate)
    }

    @Test fun recentMerchantsShowOnlyWhileMerchantEmpty() = runTest {
        val existing = Transaction("t1", TxKind.Expense, 45_000, "food", "a1", null, "Swiggy", null, "2026-10-13", "13:05")
        val h = start(repo(txs = listOf(existing)))
        assertEquals(listOf("Swiggy"), h.state.recentMerchants)
        h.vm.onAction(TxEditorAction.Merchant("Zo"))
        assertEquals(emptyList(), h.state.recentMerchants)
    }

    @Test fun transferSelectsTransferCategory() = runTest {
        val h = start(repo())
        assertTrue(h.state.showCategories)
        h.vm.onAction(TxEditorAction.Kind(TxKind.Transfer))
        assertEquals("xfer", h.state.categoryId)
        assertFalse(h.state.showCategories)
        assertEquals("a2", h.state.toAccountId)
        assertEquals(listOf("a2"), h.state.toAccounts.map { it.id })
        assertEquals("Save transfer", h.state.saveLabel)
        h.vm.onAction(TxEditorAction.Kind(TxKind.Income))
        assertEquals("salary", h.state.categoryId)
        assertEquals(listOf("salary"), h.state.categories.map { it.id })
        assertEquals("Source", h.state.merchantLabel)
        assertEquals("e.g. Salary — Beyond Labs", h.state.merchantPlaceholder)
    }

    @Test fun accountLabelsShowSignedBalance() = runTest {
        val h = start(repo())
        assertEquals(listOf("HDFC ••1234 · ₹12,400", "ICICI ••9876 · −₹3,000"), h.state.accounts.map { it.label })
    }

    @Test fun newCategoryIsAddedAndSelected() = runTest {
        val r = repo()
        val h = start(r)
        h.vm.onAction(TxEditorAction.NewCategory("  Gifts ", "🎁"))
        runCurrent()
        assertTrue("addCategory:Gifts" in r.calls)
        assertEquals("cat1", h.state.categoryId)
        assertEquals(listOf("Category added · 🎁 Gifts"), h.snacks)
    }
}
