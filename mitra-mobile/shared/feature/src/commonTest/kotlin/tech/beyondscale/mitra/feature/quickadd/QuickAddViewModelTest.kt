package tech.beyondscale.mitra.feature.quickadd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.testing.FakeMoneyRepository
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class QuickAddViewModelTest : ViewModelTest() {
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"), TimeZone.of("Asia/Kolkata"))
    private val work = Project("p1", "Work", "💼")
    private val text = "Call John tomorrow p1 #work"

    private class Harness(val vm: QuickAddViewModel, val events: List<UiEvent<QuickAddAction>>)

    private val food = Category("c1", "Food & dining", "🍛", CategoryKind.Expense, slug = "food")
    private val acct = Account("a1", "HDFC", "1234", AccountKind.Bank, 100_000_00)

    private fun TestScope.start(repo: FakeTasksRepository, money: FakeMoneyRepository = FakeMoneyRepository(listOf(acct), listOf(food))): Harness {
        val vm = QuickAddViewModel(repo, money, clock)
        val events = mutableListOf<UiEvent<QuickAddAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, events)
    }

    @Test fun parsesAsYouType() = runTest {
        val h = start(FakeTasksRepository(projects = listOf(work)))
        h.vm.onAction(QuickAddAction.Text(text))
        runCurrent()
        val s = h.vm.state.value
        assertEquals("Call John", s.title)
        assertEquals("Tomorrow", s.dueLabel)
        assertEquals("High", s.priLabel)
        assertEquals("💼 Work", s.projectLabel)
    }

    @Test fun clearingAChipPutsWordBack() = runTest {
        val h = start(FakeTasksRepository(projects = listOf(work)))
        h.vm.onAction(QuickAddAction.Text(text))
        h.vm.onAction(QuickAddAction.ClearDue)
        runCurrent()
        assertEquals("Call John tomorrow", h.vm.state.value.title)
        assertNull(h.vm.state.value.dueLabel)
    }

    @Test fun saveCreatesAndOffersUndo() = runTest {
        val repo = FakeTasksRepository(projects = listOf(work))
        val h = start(repo)
        h.vm.onAction(QuickAddAction.Text(text))
        h.vm.onAction(QuickAddAction.Save)
        runCurrent()
        assertEquals(listOf("create:Call John"), repo.calls)
        assertEquals(
            listOf(
                UiEvent.Snack("Added · Call John · Tomorrow", QuickAddAction.DeleteCreated("new1")),
                UiEvent.Haptic(HapticKind.Confirm),
                UiEvent.Close,
            ),
            h.events,
        )
        assertEquals("", h.vm.state.value.text)
    }

    @Test fun createdTaskCarriesParsedFields() = runTest {
        val repo = FakeTasksRepository(projects = listOf(work))
        val h = start(repo)
        h.vm.onAction(QuickAddAction.Text(text))
        h.vm.onAction(QuickAddAction.Save)
        runCurrent()
        val created = repo.tasks.first().single()
        assertEquals("2026-10-14", created.due)
        assertEquals(Priority.High, created.pri)
        assertEquals("p1", created.projectId)
    }

    @Test fun doubleSaveCreatesOnce() = runTest {
        val repo = FakeTasksRepository(projects = listOf(work))
        val h = start(repo)
        h.vm.onAction(QuickAddAction.Text(text))
        h.vm.onAction(QuickAddAction.Save)
        h.vm.onAction(QuickAddAction.Save)
        runCurrent()
        assertEquals(listOf("create:Call John"), repo.calls)
    }

    @Test fun blankTitleIsRejected() = runTest {
        val repo = FakeTasksRepository(projects = listOf(work))
        val h = start(repo)
        h.vm.onAction(QuickAddAction.Text("today p1"))
        h.vm.onAction(QuickAddAction.Save)
        runCurrent()
        assertEquals("Give the task a title", h.vm.state.value.error)
        assertEquals(emptyList(), repo.calls)
    }

    @Test fun expenseChipParsesAmountAndCategory() = runTest {
        val h = start(FakeTasksRepository())
        h.vm.onAction(QuickAddAction.Type(QuickType.Expense))
        h.vm.onAction(QuickAddAction.Text("₹240 food lunch"))
        runCurrent()
        val s = h.vm.state.value
        assertEquals("₹240", s.amountLabel)
        assertEquals("🍛 Food & dining", s.categoryLabel)
        assertTrue(s.canSave)
    }

    @Test fun saveExpenseUsesDefaultAccountAndLocalDate() = runTest {
        val money = FakeMoneyRepository(listOf(acct), listOf(food))
        val late = FixedClock(Instant.parse("2026-10-31T19:00:00Z"), TimeZone.of("Asia/Kolkata")) // 2026-11-01 00:30 IST
        val vm = QuickAddViewModel(FakeTasksRepository(), money, late)
        val events = mutableListOf<UiEvent<QuickAddAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        vm.onAction(QuickAddAction.Type(QuickType.Expense))
        vm.onAction(QuickAddAction.Text("₹240 food lunch"))
        vm.onAction(QuickAddAction.Save)
        runCurrent()
        val tx = money.transactionFlow.value.single()
        assertEquals("2026-11-01", tx.date)
        assertEquals("00:30", tx.time)
        assertEquals("a1", tx.accountId)
        assertEquals("c1", tx.categoryId)
        assertEquals(24_000L, tx.amountPaise)
        assertEquals(TxKind.Expense, tx.kind)
        assertEquals("food lunch", tx.merchant)
        assertEquals(UiEvent.Snack("Expense saved · ₹240", QuickAddAction.UndoExpense("tx1")), events.first())
        vm.onAction(QuickAddAction.UndoExpense("tx1"))
        runCurrent()
        assertEquals(emptyList(), money.transactionFlow.value)
    }

    @Test fun clearAmountDisablesSave() = runTest {
        val h = start(FakeTasksRepository())
        h.vm.onAction(QuickAddAction.Type(QuickType.Expense))
        h.vm.onAction(QuickAddAction.Text("₹240 food"))
        h.vm.onAction(QuickAddAction.ClearAmount)
        runCurrent()
        assertFalse(h.vm.state.value.canSave)
        assertNull(h.vm.state.value.amountLabel)
    }

    @Test fun noAccountShowsError() = runTest {
        val money = FakeMoneyRepository(categories = listOf(food))
        val h = start(FakeTasksRepository(), money)
        h.vm.onAction(QuickAddAction.Type(QuickType.Expense))
        h.vm.onAction(QuickAddAction.Text("₹240 food"))
        h.vm.onAction(QuickAddAction.Save)
        runCurrent()
        assertEquals("Add an account first", h.vm.state.value.error)
        assertEquals(emptyList(), money.calls)
    }

    @Test fun otherTypesStillArriveLater() = runTest {
        val repo = FakeTasksRepository()
        val h = start(repo)
        h.vm.onAction(QuickAddAction.Text("Lunch 200"))
        h.vm.onAction(QuickAddAction.Type(QuickType.Habit))
        h.vm.onAction(QuickAddAction.Save)
        runCurrent()
        assertEquals("Habit check arrives with Habits — add it on the web for now.", h.vm.state.value.notice)
        assertFalse(h.vm.state.value.canSave)
        assertEquals(emptyList(), repo.calls)
    }
}
