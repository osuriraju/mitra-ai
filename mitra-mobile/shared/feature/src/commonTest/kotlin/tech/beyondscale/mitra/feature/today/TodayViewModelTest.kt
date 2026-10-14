package tech.beyondscale.mitra.feature.today

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.flow.toList
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FakeMoneyRepository
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import tech.beyondscale.mitra.testing.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TodayViewModelTest : ViewModelTest() {
    // 04:30Z = 10:00 in India
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"), TimeZone.of("Asia/Kolkata"))

    @Test fun greetsByFirstNameInLocalTime() {
        val vm = TodayViewModel(FakeAuthRepository(SessionState.SignedIn(testUser())), FakeTasksRepository(today = "2026-10-13"), FakeMoneyRepository(), clock)
        assertEquals("Good morning, Raju", vm.state.value.greeting)
        assertEquals("Tuesday, 13 October 2026 · 10:00 AM", vm.state.value.dateLine)
        assertEquals(listOf("Tasks", "Habits", "Spent today", "Sleep"), vm.state.value.stats.map { it.label })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun headerRefreshesAsTheClockAdvances() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = TodayViewModel(FakeAuthRepository(SessionState.SignedIn(testUser())), FakeTasksRepository(today = "2026-10-13"), FakeMoneyRepository(), clock)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals("Good morning, Raju", vm.state.value.greeting)

        clock.instant = Instant.parse("2026-10-13T06:30:00Z") // 12:00 IST
        advanceTimeBy(60_001)
        assertEquals("Good afternoon, Raju", vm.state.value.greeting)
        assertEquals("Tuesday, 13 October 2026 · 12:00 PM", vm.state.value.dateLine)
    }

    private val today = "2026-10-13"

    private fun TestScope.vmWith(tasks: List<Task>, settings: Settings = Settings(), money: FakeMoneyRepository = FakeMoneyRepository()): Pair<TodayViewModel, FakeTasksRepository> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeTasksRepository(tasks, today = today)
        val vm = TodayViewModel(FakeAuthRepository(SessionState.SignedIn(testUser(settings = settings))), repo, money, clock)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm to repo
    }

    private val mixed = listOf(
        Task("d", "Done", due = today, done = true, completedAt = today),
        Task("a", "Open A", due = today, estMin = 30, pri = Priority.Low),
        Task("b", "Open B", due = today, estMin = 45, pri = Priority.High),
        Task("o", "Late", due = "2026-10-10"),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun tasksStatMatchesWeb() = runTest {
        val (vm, _) = vmWith(mixed)
        val stat = vm.state.value.stats.first()
        assertEquals("1/3", stat.value)
        assertEquals("1 overdue · 1h 15m planned · 0m done", stat.sub)
        assertEquals(StatTarget.Tasks, stat.target)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun prioritiesAreTopThree() = runTest {
        val (vm, _) = vmWith(mixed)
        val s = vm.state.value
        assertEquals(listOf("o", "b", "a"), s.priorities.map { it.id })
        assertEquals("All 4 tasks for today", s.prioritiesFooter)
        assertEquals("1 done · 1 overdue", s.prioritiesMeta)
        assertEquals(true, s.showPriorities)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun prioritiesHiddenWhenSectionRemoved() = runTest {
        val (vm, _) = vmWith(mixed, Settings(todaySections = listOf("habits")))
        assertEquals(false, vm.state.value.showPriorities)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun toggleFromTodayOffersUndo() = runTest {
        val (vm, repo) = vmWith(mixed)
        val events = mutableListOf<UiEvent<TodayAction>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        vm.onAction(TodayAction.Toggle("b"))
        runCurrent()
        val undo = TodayAction.UndoComplete("b", null)
        assertEquals(listOf<UiEvent<TodayAction>>(UiEvent.Haptic(HapticKind.Confirm), UiEvent.Snack("Completed · Open B", undo)), events)
        vm.onAction(undo)
        runCurrent()
        assertEquals(false, repo.taskFlow.value.first { it.id == "b" }.done)
    }

    private fun tx(id: String, amount: Long, date: String) =
        Transaction(id, TxKind.Expense, amount, "c1", "a1", null, "m", null, date, "10:00")

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun spentTodayStat() = runTest {
        val cat = Category("c1", "Food", "🍛", CategoryKind.Expense, budgetPaise = 3_000_000)
        val money = FakeMoneyRepository(
            categories = listOf(cat),
            transactions = listOf(tx("t1", 45_000, today), tx("t2", 1_795_000, "2026-10-02")),
        )
        val (vm, _) = vmWith(emptyList(), money = money)
        val stat = vm.state.value.stats.first { it.label == "Spent today" }
        assertEquals("₹450", stat.value)
        assertEquals("61% of month budget used", stat.sub)
        assertEquals(StatTarget.Money, stat.target)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun moneyCardRollsOverAtLocalMidnight() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val night = FixedClock(Instant.parse("2026-10-31T18:29:00Z"), TimeZone.of("Asia/Kolkata")) // 23:59 IST
        val cat = Category("c1", "Food", "🍛", CategoryKind.Expense, budgetPaise = 3_000_000)
        val money = FakeMoneyRepository(categories = listOf(cat), transactions = listOf(tx("t1", 45_000, "2026-10-31")))
        val vm = TodayViewModel(FakeAuthRepository(SessionState.SignedIn(testUser())), FakeTasksRepository(today = "2026-10-31"), money, night)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals("1 day left in Oct", vm.state.value.money?.daysLeft)
        assertEquals("₹450", vm.state.value.stats.first { it.label == "Spent today" }.value)

        night.instant = Instant.parse("2026-10-31T18:30:30Z") // 00:00:30 IST on 1 Nov
        advanceTimeBy(60_002)
        assertEquals("30 days left in Nov", vm.state.value.money?.daysLeft)
        assertEquals("₹0", vm.state.value.stats.first { it.label == "Spent today" }.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun moneyHiddenWhenModuleOff() = runTest {
        val (vm, _) = vmWith(emptyList(), Settings(modules = Modules(money = false)))
        assertEquals(null, vm.state.value.money)
    }
}
