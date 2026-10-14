package tech.beyondscale.mitra.feature.goals

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dowMon
import tech.beyondscale.mitra.domain.goals.StatusTone
import tech.beyondscale.mitra.domain.model.ALL_DAYS
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.ReviewEvery
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TimeOfDay
import tech.beyondscale.mitra.testing.FakeGoalsRepository
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val TODAY = "2026-10-14"

/** A goal for feature tests: numeric, Medium-term, started 1 Oct, due 31 Mar 2027, review next month, logged today. */
private fun testGoal(
    id: String,
    name: String = id,
    type: GoalType = GoalType.Numeric,
    term: GoalTerm = GoalTerm.Medium,
    due: String = "2027-03-31",
    current: Double = 10.0,
    target: Double = 100.0,
    reviewOn: String = "2026-11-14",
    reviewEvery: ReviewEvery = ReviewEvery.Monthly,
    state: GoalState = GoalState.Active,
    nextAction: String = "",
) = Goal(
    id = id, name = name, type = type, term = term, due = due, startedOn = "2026-10-01", current = current,
    target = target, unit = "", formula = null, nextAction = nextAction, reviewEvery = reviewEvery, reviewOn = reviewOn,
    state = state, notes = "", createdAt = "", milestones = emptyList(), progressLog = listOf(ProgressEntry(TODAY, current)),
)

@OptIn(ExperimentalCoroutinesApi::class)
class GoalsViewModelTest : ViewModelTest() {
    // 08:00Z = 13:30 on Wed 14 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-14T08:00:00Z"), TimeZone.of("Asia/Kolkata"))

    private fun habit(id: String, goalId: String?, done: Int): Habit {
        val h = Habit(id, id, HabitType.Binary, 1, "", "repeat", TimeOfDay.Anytime, ALL_DAYS, null, goalId, true, false, "", emptyMap())
        val due = (29 downTo 0).map { addDays(TODAY, -it) }.filter { ALL_DAYS[dowMon(it)] }
        return h.copy(logs = due.take(done).associateWith { 1 })
    }

    private fun TestScope.start(
        goals: List<Goal>,
        habits: List<Habit> = emptyList(),
        tasks: List<Task> = emptyList(),
        projects: List<Project> = emptyList(),
    ): GoalsViewModel {
        val vm = GoalsViewModel(FakeGoalsRepository(goals, clock), FakeHabitsRepository(habits), FakeTasksRepository(tasks, projects), clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test fun tabsAndSummary() = runTest {
        val vm = start(
            listOf(
                testGoal("a", reviewOn = TODAY),
                testGoal("b"),
                testGoal("p", state = GoalState.Paused, reviewOn = "2026-10-01"),
                testGoal("c", state = GoalState.Completed),
            ),
        )
        val s = vm.state.value
        assertEquals(listOf("a", "b"), s.active.map { it.id })
        assertEquals(listOf("a"), s.reviewDue.map { it.id })
        assertEquals(listOf("p", "c"), s.completed.map { it.id })
        assertEquals("2 active · 1 review due", s.summary)
        assertTrue(s.showStartReview)
        assertEquals(listOf("a", "b"), s.cards.map { it.id })
        assertNull(s.empty)

        vm.onAction(GoalsAction.Tab(1))
        assertEquals(listOf("a"), vm.state.value.cards.map { it.id })
        vm.onAction(GoalsAction.Tab(2))
        assertEquals(listOf("p", "c"), vm.state.value.cards.map { it.id })
        // Paused goals are dimmed; completed ones are not.
        assertEquals(listOf(true, false), vm.state.value.cards.map { it.dimmed })
    }

    @Test fun emptyTabsCarryTheirCopy() = runTest {
        val vm = start(listOf(testGoal("b")))
        assertNull(vm.state.value.empty)
        assertFalse(vm.state.value.showStartReview)
        assertEquals("1 active · 0 review due", vm.state.value.summary)
        vm.onAction(GoalsAction.Tab(1))
        assertEquals(GoalsEmpty.ReviewDue, vm.state.value.empty)
        assertEquals("No reviews due", vm.state.value.empty?.title)
        vm.onAction(GoalsAction.Tab(2))
        assertEquals("Nothing completed or paused yet", vm.state.value.empty?.title)
        assertFalse(GoalsEmpty.Completed.showNew)
        assertTrue(GoalsEmpty.Active.showNew)
    }

    @Test fun cardMetaAndLinks() = runTest {
        val g = testGoal("g", name = "Emergency fund", nextAction = "Move ₹8,000 on payday")
        val tasks = listOf(Task("t1", "A", goalId = "g"), Task("t2", "B", goalId = "g", done = true), Task("t3", "C", goalId = "g"), Task("t4", "D"))
        val projects = listOf(Project("p1", "Savings", goalId = "g"), Project("p2", "Other"))
        val habits = listOf(habit("h1", "g", 30), habit("h2", "g", 10), habit("h3", null, 0))
        val bare = testGoal("bare", reviewOn = TODAY)
        val vm = start(listOf(g, bare), habits, tasks, projects)
        val card = vm.state.value.active.first { it.id == "g" }
        assertEquals("Numeric · Medium-term · due Mar 2027", card.meta)
        assertEquals("1 project · 3 tasks · 2 habits", card.links)
        assertEquals("Move ₹8,000 on payday", card.next)
        assertEquals(10, card.pct)
        val none = vm.state.value.active.first { it.id == "bare" }
        assertEquals("Nothing linked yet · review due", none.links)
        assertNull(none.next)
    }

    @Test fun habitGoalUsesLinkedHabits() = runTest {
        val g = testGoal("g", type = GoalType.Habit, current = 22.0)
        val vm = start(listOf(g), habits = listOf(habit("h1", "g", 30), habit("h2", "g", 15), habit("h3", "other", 0)))
        val card = vm.state.value.cards.single()
        assertEquals(75, card.pct) // (100 + 50) / 2
        assertEquals("Habit-supported · Medium-term · due Mar 2027", card.meta)
    }

    @Test fun statusAndRingTones() = runTest {
        val done = testGoal("c", state = GoalState.Completed)
        val stale = testGoal("s", current = 0.0).copy(startedOn = "2026-01-01", due = "2026-12-31", progressLog = emptyList())
        val vm = start(listOf(done, stale))
        vm.onAction(GoalsAction.Tab(2))
        val c = vm.state.value.cards.single()
        assertEquals("Completed", c.status)
        assertEquals(StatusTone.Accent, c.statusTone)
        assertEquals(StatusTone.Success, c.ringTone)
        vm.onAction(GoalsAction.Tab(0))
        val s = vm.state.value.cards.single()
        assertEquals("Slipping", s.status)
        assertEquals(StatusTone.Danger, s.statusTone)
        assertEquals(StatusTone.Danger, s.ringTone)
    }

    @Test fun reviewDueRollsOverAtMidnight() = runTest {
        val vm = start(listOf(testGoal("a", reviewOn = "2026-10-15")))
        assertEquals(0, vm.state.value.reviewDue.size)
        clock.instant = Instant.parse("2026-10-14T18:31:00Z") // 00:01 on 15 Oct in India
        testScheduler.advanceTimeBy(24 * 60 * 60 * 1000L)
        runCurrent()
        assertEquals(1, vm.state.value.reviewDue.size)
    }
}
