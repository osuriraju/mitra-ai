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
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.feature.habits.logRun
import tech.beyondscale.mitra.feature.habits.testHabit
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import tech.beyondscale.mitra.testing.FakeNotesRepository
import tech.beyondscale.mitra.testing.FakeWellnessRepository
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

    private fun TestScope.start(
        repo: FakeTasksRepository,
        money: FakeMoneyRepository = FakeMoneyRepository(listOf(acct), listOf(food)),
        habits: FakeHabitsRepository = FakeHabitsRepository(),
        clock: FixedClock = this@QuickAddViewModelTest.clock,
        notes: FakeNotesRepository = FakeNotesRepository(),
        wellness: FakeWellnessRepository = FakeWellnessRepository(),
    ): Harness {
        val vm = QuickAddViewModel(repo, money, habits, notes, wellness, clock)
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
        val vm = QuickAddViewModel(FakeTasksRepository(), money, FakeHabitsRepository(), FakeNotesRepository(), FakeWellnessRepository(), late)
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

    @Test fun noteCreatesTheNoteAndOpensItsEditor() = runTest {
        val repo = FakeTasksRepository()
        val notes = FakeNotesRepository()
        val h = start(repo, notes = notes)
        val opened = mutableListOf<QuickAddEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.vm.navigation.toList(opened) }
        h.vm.onAction(QuickAddAction.Type(QuickType.Note))
        assertFalse(h.vm.state.value.canSave)
        h.vm.onAction(QuickAddAction.Text("Read later\nhttps://example.com"))
        h.vm.onAction(QuickAddAction.Save)
        runCurrent()
        val n = notes.noteFlow.value.single()
        assertEquals("Read later", n.title)
        assertEquals("https://example.com", n.body)
        assertEquals(listOf<QuickAddEvent>(QuickAddEvent.OpenNote(n.id)), opened)
        assertTrue(h.events.any { it is UiEvent.Close })
        assertEquals(emptyList(), repo.calls)
    }

    @Test fun moodLogsTodayAndCloses() = runTest {
        val wellness = FakeWellnessRepository()
        val h = start(FakeTasksRepository(), wellness = wellness)
        h.vm.onAction(QuickAddAction.Type(QuickType.Mood))
        h.vm.onAction(QuickAddAction.Mood(4))
        runCurrent()
        assertEquals(4, wellness.entryFlow.value["2026-10-13"]?.mood)
        assertTrue(h.events.any { it is UiEvent.Snack && it.text.startsWith("Mood logged") })
        assertTrue(h.events.any { it is UiEvent.Close })
    }

    // 10:00 IST on Wed 14 Oct (testHabit / logRun's TODAY)
    private val oct14 = FixedClock(Instant.parse("2026-10-14T04:30:00Z"), TimeZone.of("Asia/Kolkata"))

    @Test fun habitCheckListsDueHabitsFilteredByText() = runTest {
        val weekend = listOf(false, false, false, false, false, true, true)
        val habits = FakeHabitsRepository(listOf(testHabit("read"), testHabit("bread", "Bake bread", days = weekend), testHabit("walk")))
        val h = start(FakeTasksRepository(), habits = habits, clock = oct14)
        h.vm.onAction(QuickAddAction.Type(QuickType.Habit))
        h.vm.onAction(QuickAddAction.Text("rea"))
        runCurrent()
        assertEquals(listOf("Read"), h.vm.state.value.habitRows.map { it.name })
        assertFalse(h.vm.state.value.canSave)

        h.vm.onAction(QuickAddAction.Text(""))
        runCurrent()
        assertEquals(listOf("Read", "Walk"), h.vm.state.value.habitRows.map { it.name })
    }

    @Test fun checkFromQuickAddLogsTodayAndSnacks() = runTest {
        val habits = FakeHabitsRepository(listOf(testHabit("read", logs = logRun(3, daysAgoEnd = 1))))
        val h = start(FakeTasksRepository(), habits = habits, clock = oct14)
        h.vm.onAction(QuickAddAction.Type(QuickType.Habit))
        h.vm.onAction(QuickAddAction.CheckHabit("read"))
        runCurrent()
        assertEquals("log:read:2026-10-14:1", habits.calls.last())
        val undo = QuickAddAction.UndoHabit("read", "2026-10-14", null)
        assertTrue(UiEvent.Snack("Read ✓ · streak 4", undo) in h.events)
        assertTrue(h.vm.state.value.habitRows.single().done)

        h.vm.onAction(undo)
        runCurrent()
        assertEquals("log:read:2026-10-14:null", habits.calls.last())
    }

    @Test fun newHabitCarriesTypedName() = runTest {
        val h = start(FakeTasksRepository(), clock = oct14)
        val nav = mutableListOf<QuickAddEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.vm.navigation.toList(nav) }
        h.vm.onAction(QuickAddAction.Type(QuickType.Habit))
        h.vm.onAction(QuickAddAction.Text("Stretch"))
        h.vm.onAction(QuickAddAction.NewHabit)
        runCurrent()
        assertEquals(listOf<QuickAddEvent>(QuickAddEvent.NewHabit("Stretch")), nav)
    }
}
