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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tech.beyondscale.mitra.testing.FakeWellnessRepository
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.testing.FakeMoneyRepository
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FakeGoalsRepository
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.domain.goals.StatusTone
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.ReviewEvery
import tech.beyondscale.mitra.feature.habits.logRun
import tech.beyondscale.mitra.feature.habits.testHabit
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
        val vm = TodayViewModel(FakeAuthRepository(SessionState.SignedIn(testUser())), FakeTasksRepository(today = "2026-10-13"), FakeMoneyRepository(), FakeHabitsRepository(), FakeGoalsRepository(clock = clock), clock, FakeWellnessRepository())
        assertEquals("Good morning, Raju", vm.state.value.greeting)
        assertEquals("Tuesday, 13 October 2026 · 10:00 AM", vm.state.value.dateLine)
        assertEquals(listOf("Tasks", "Habits", "Spent today", "Sleep"), vm.state.value.stats.map { it.label })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun headerRefreshesAsTheClockAdvances() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = TodayViewModel(FakeAuthRepository(SessionState.SignedIn(testUser())), FakeTasksRepository(today = "2026-10-13"), FakeMoneyRepository(), FakeHabitsRepository(), FakeGoalsRepository(clock = clock), clock, FakeWellnessRepository())
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
        val vm = TodayViewModel(FakeAuthRepository(SessionState.SignedIn(testUser(settings = settings))), repo, money, FakeHabitsRepository(), FakeGoalsRepository(clock = clock), clock, FakeWellnessRepository())
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
        val vm = TodayViewModel(FakeAuthRepository(SessionState.SignedIn(testUser())), FakeTasksRepository(today = "2026-10-31"), money, FakeHabitsRepository(), FakeGoalsRepository(clock = night), night, FakeWellnessRepository())
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

    // ---- Habits and Goals (Phase 4) ----

    private fun TestScope.vmHG(
        c: AppClock,
        habits: List<Habit> = emptyList(),
        goals: List<Goal> = emptyList(),
        settings: Settings = Settings(),
        wellness: FakeWellnessRepository = FakeWellnessRepository(),
    ): Pair<TodayViewModel, FakeHabitsRepository> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeHabitsRepository(habits)
        val vm = TodayViewModel(
            FakeAuthRepository(SessionState.SignedIn(testUser(settings = settings))), FakeTasksRepository(today = "2026-10-14"),
            FakeMoneyRepository(), repo, FakeGoalsRepository(goals, c), c, wellness,
        )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm to repo
    }

    // 10:00 IST on Wed 14 Oct (testHabit / logRun's TODAY)
    private val oct14 = FixedClock(Instant.parse("2026-10-14T04:30:00Z"), TimeZone.of("Asia/Kolkata"))

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun habitsStatCountsDueAndDone() = runTest {
        val (vm, _) = vmHG(
            oct14,
            habits = listOf(
                testHabit("meditate", logs = logRun(12)),
                testHabit("journal"),
                testHabit("walk", logs = logRun(5, daysAgoEnd = 2)),
            ),
        )
        val stat = vm.state.value.stats.first { it.label == "Habits" }
        assertEquals("1/3", stat.value)
        assertEquals("57% this week", stat.sub)
        assertEquals(StatTarget.Habits, stat.target)
        assertEquals(listOf("meditate", "journal", "walk"), vm.state.value.habits?.map { it.id })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun habitCheckUsesLocalDateAfterMidnight() = runTest {
        val late = FixedClock(Instant.parse("2026-10-31T19:00:00Z"), TimeZone.of("Asia/Kolkata")) // 2026-11-01 00:30 IST
        val (vm, repo) = vmHG(late, habits = listOf(testHabit("read")))
        vm.onAction(TodayAction.CheckHabit("read"))
        runCurrent()
        assertEquals("log:read:2026-11-01:1", repo.calls.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun habitsStatRollsOverAtMidnight() = runTest {
        val night = FixedClock(Instant.parse("2026-10-31T18:29:00Z"), TimeZone.of("Asia/Kolkata")) // 23:59 IST on 31 Oct
        val (vm, _) = vmHG(night, habits = listOf(testHabit("read", logs = mapOf("2026-10-31" to 1))))
        assertEquals("1/1", vm.state.value.stats.first { it.label == "Habits" }.value)

        night.instant = Instant.parse("2026-10-31T18:30:30Z") // 00:00:30 IST on 1 Nov
        advanceTimeBy(60_002)
        assertEquals("0/1", vm.state.value.stats.first { it.label == "Habits" }.value)
    }

    private fun goal(id: String, current: Double, logged: Boolean, nextAction: String = "") = Goal(
        id = id, name = id, type = GoalType.Numeric, term = GoalTerm.Medium, due = "2026-12-31", startedOn = "2026-01-01",
        current = current, target = 100.0, unit = "", formula = null, nextAction = nextAction, reviewEvery = ReviewEvery.Monthly,
        reviewOn = "2026-11-14", state = GoalState.Active, notes = "", createdAt = "", milestones = emptyList(),
        progressLog = if (logged) listOf(ProgressEntry("2026-10-14", current)) else emptyList(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun goalsCardShowsNotOnTrackFirst() = runTest {
        // ~78% of the year elapsed: 80 logged is on track, 50 logged is behind, 50 never logged is slipping.
        val (vm, _) = vmHG(
            oct14,
            goals = listOf(
                goal("ontrack", 80.0, logged = true, nextAction = "Transfer ₹8,000"),
                goal("behind", 50.0, logged = true),
                goal("slipping", 50.0, logged = false),
            ),
        )
        val rows = vm.state.value.goals!!
        assertEquals(listOf("behind", "slipping"), rows.map { it.id })
        assertEquals(listOf("Behind", "Slipping"), rows.map { it.status })
        assertEquals(listOf(StatusTone.Warning, StatusTone.Danger), rows.map { it.tone })
        assertEquals("Next: —", rows.first().next)
        assertEquals(50, rows.first().pct)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun sectionsFollowTodaySectionsOrder() = runTest {
        val (vm, _) = vmHG(oct14, settings = Settings(todaySections = listOf("goals", "priorities", "habits")))
        assertEquals(listOf("goals", "priorities", "habits"), vm.state.value.sections)
        assertEquals(emptyList(), vm.state.value.habits)
        assertEquals(null, vm.state.value.money)

        val (noHabits, _) = vmHG(oct14, settings = Settings(todaySections = listOf("goals", "activity", "money")))
        assertEquals(listOf("goals", "money"), noHabits.state.value.sections)
        assertEquals(null, noHabits.state.value.habits)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun checkinCardFollowsShowOnTodayAndSavesTodaysMood() = runTest {
        val wellness = FakeWellnessRepository()
        val (vm, _) = vmHG(oct14, wellness = wellness)
        assertTrue("checkin" in vm.state.value.sections)

        vm.onAction(TodayAction.Mood(3)); runCurrent()
        assertEquals(3, wellness.entryFlow.value["2026-10-14"]?.mood)
        assertEquals(3, vm.state.value.mood)
        vm.onAction(TodayAction.Mood(3)); runCurrent() // tapping today's mood clears it
        assertEquals(null, wellness.entryFlow.value["2026-10-14"]?.mood)

        wellness.settingsFlow.value = WellnessSettings(showOnToday = false); runCurrent()
        assertFalse("checkin" in vm.state.value.sections)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun checkinCardHiddenWithWellnessModuleOff() = runTest {
        val (vm, _) = vmHG(oct14, settings = Settings(modules = Modules(wellness = false)))
        assertFalse("checkin" in vm.state.value.sections)
    }
}
