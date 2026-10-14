package tech.beyondscale.mitra.feature.goals

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitDraft
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.MilestoneChange
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.ReviewEvery
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.toHabit
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.feature.common.CompletionUndo
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeGoalsRepository
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The web seed's emergency fund (₹58,000 of ₹1,00,000) and friends; today is Wed 14 Oct 2026 in India. */
internal object GoalDetailFixtures {
    const val TODAY = "2026-10-14"

    fun fund(state: GoalState = GoalState.Active) = Goal(
        id = "g1", name = "Build ₹1,00,000 emergency fund", type = GoalType.Numeric, term = GoalTerm.Medium,
        due = "2027-04-12", startedOn = "2026-04-01", current = 58000.0, target = 100000.0, unit = "₹", formula = null,
        nextAction = "Transfer ₹8,000", reviewEvery = ReviewEvery.Monthly, reviewOn = "2026-11-01", state = state, notes = "",
        createdAt = "2026-04-01T00:00:00.000Z",
        milestones = listOf(
            Milestone("m1", "₹25,000", 25000.0, "2026-06-01", reachedOn = "2026-06-20"),
            Milestone("m2", "₹50,000", 50000.0, "2026-09-01", reachedOn = "2026-10-12"),
            Milestone("m3", "₹75,000", 75000.0, "2027-01-14"),
            Milestone("m4", "₹1,00,000", 100000.0, null),
        ),
        progressLog = listOf(ProgressEntry("2026-08-15", 42000.0), ProgressEntry(TODAY, 58000.0, "bonus")),
    )

    fun marathon() = Goal(
        id = "gm", name = "Run a half marathon", type = GoalType.Milestone, term = GoalTerm.Short,
        due = "2027-01-31", startedOn = "2026-09-01", current = 2.0, target = 4.0, unit = "", formula = null,
        nextAction = "", reviewEvery = ReviewEvery.Weekly, reviewOn = "2026-10-20", state = GoalState.Active, notes = "",
        createdAt = "2026-09-01T00:00:00.000Z",
        milestones = listOf(
            Milestone("r1", "5 km", reachedOn = "2026-09-10"),
            Milestone("r2", "10 km", reachedOn = "2026-10-01"),
            Milestone("r3", "15 km"),
            Milestone("r4", "21 km"),
        ),
        progressLog = listOf(ProgressEntry("2026-09-10", 1.0, "5 km"), ProgressEntry("2026-10-01", 2.0, "10 km")),
    )

    fun fitness() = Goal(
        id = "gh", name = "Get fitter", type = GoalType.Habit, term = GoalTerm.Long,
        due = "2027-06-30", startedOn = "2026-07-01", current = 0.0, target = 100.0, unit = "", formula = null,
        nextAction = "", reviewEvery = ReviewEvery.Quarterly, reviewOn = "2026-12-01", state = GoalState.Active, notes = "",
        createdAt = "2026-07-01T00:00:00.000Z", milestones = emptyList(), progressLog = emptyList(),
    )

    /** A daily binary habit linked to [goalId], done on [done] of the last 30 days (today included). */
    fun habit(id: String, goalId: String, done: Int): Habit = HabitDraft(id).toHabit(id, createdAt = "").copy(
        goalId = goalId,
        logs = (0 until done).associate { addDays(TODAY, -it) to 1 },
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class GoalDetailViewModelTest : ViewModelTest() {
    // 10:00 on Wed 14 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-14T04:30:00Z"), TimeZone.of("Asia/Kolkata"))

    /** Records milestone patches with their changes, then applies them through the fake. */
    private class Recording(val fake: FakeGoalsRepository) : GoalsRepository by fake {
        val milestoneChanges = mutableListOf<Pair<String, List<MilestoneChange>>>()
        val goalChanges = mutableListOf<Pair<String, List<GoalChange>>>()
        override suspend fun updateMilestone(goalId: String, id: String, changes: List<MilestoneChange>) {
            milestoneChanges += id to changes
            fake.updateMilestone(goalId, id, changes)
        }
        override suspend fun update(id: String, changes: List<GoalChange>) {
            goalChanges += id to changes
            fake.update(id, changes)
        }
    }

    private class Harness(
        val vm: GoalDetailViewModel,
        val goals: Recording,
        val tasks: FakeTasksRepository,
        val events: List<UiEvent<GoalDetailAction>>,
    ) {
        val state get() = vm.state.value
        val snacks get() = events.filterIsInstance<UiEvent.Snack<GoalDetailAction>>().map { it.text }
        val calls get() = goals.fake.calls
    }

    private fun TestScope.harness(
        id: String,
        goals: List<Goal> = listOf(GoalDetailFixtures.fund()),
        habits: List<Habit> = emptyList(),
        tasks: List<Task> = emptyList(),
        projects: List<Project> = emptyList(),
        clock: FixedClock = this@GoalDetailViewModelTest.clock,
    ): Harness {
        val goalRepo = Recording(FakeGoalsRepository(goals, clock))
        val taskRepo = FakeTasksRepository(tasks = tasks, projects = projects)
        val vm = GoalDetailViewModel(id, goalRepo, FakeHabitsRepository(habits), taskRepo, clock)
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        val events = mutableListOf<UiEvent<GoalDetailAction>>()
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, goalRepo, taskRepo, events)
    }

    @Test fun progressKpiPerType() = runTest {
        val fund = harness("g1").state
        assertEquals("₹58,000", fund.kpiValue)
        assertEquals("of ₹1,00,000", fund.kpiSub)
        assertEquals("Numeric goal · Medium-term · Review monthly", fund.eyebrow)
        assertEquals("58% · started 1 Apr 2026", fund.startedLine)
        assertEquals("Due 12 Apr 2027 · 6 months left", fund.dueLine)
        assertEquals("₹58,000 ÷ ₹1,00,000", fund.formulaText)

        val marathon = harness("gm", goals = listOf(GoalDetailFixtures.marathon())).state
        assertEquals("2 of 4 milestones", "${marathon.kpiValue} ${marathon.kpiSub}")

        // 18/30 = 60 % and 19/30 = 63 % → (60 + 63) / 2 = 61.5 → 62 (JS half-up)
        val fit = harness(
            "gh",
            goals = listOf(GoalDetailFixtures.fitness()),
            habits = listOf(GoalDetailFixtures.habit("walk", "gh", 18), GoalDetailFixtures.habit("stretch", "gh", 19)),
        ).state
        assertEquals("62%", fit.kpiValue)
        assertEquals("habit consistency · 30d", fit.kpiSub)
    }

    @Test fun logProgressNumericAddsAndSnacks() = runTest {
        val h = harness("g1")
        h.vm.onAction(GoalDetailAction.OpenLog)
        assertTrue(h.state.logOpen)
        assertEquals("Add to ₹58,000 (target ₹1,00,000)", h.state.log?.fieldLabel)
        assertEquals("8000", h.state.log?.placeholder)

        h.vm.onAction(GoalDetailAction.LogProgress("0", ""))
        assertTrue(h.calls.none { it.startsWith("progress:") })
        assertTrue(h.state.logOpen) // an invalid amount keeps the sheet open

        h.vm.onAction(GoalDetailAction.LogProgress("8000", ""))
        assertTrue("progress:g1:8000.0" in h.calls)
        assertEquals("Progress logged · +₹8,000", h.snacks.last())
        assertEquals("₹66,000", h.state.kpiValue)
        assertEquals(false, h.state.logOpen)
        assertEquals("Last logged Today", h.state.lastLogged)
    }

    @Test fun logProgressMilestoneTypeSendsCurrent() = runTest {
        val h = harness("gm", goals = listOf(GoalDetailFixtures.marathon()))
        assertEquals(false, h.state.log?.numeric)
        h.vm.onAction(GoalDetailAction.LogProgress("", "felt strong"))
        assertTrue("progress:gm:2.0" in h.calls)
        assertEquals("Progress logged", h.snacks.last())
    }

    @Test fun tickMilestoneUsesLocalDate() = runTest {
        // 00:30 on Sun 1 Nov in India — still 31 Oct in UTC.
        val lateClock = FixedClock(Instant.parse("2026-10-31T19:00:00Z"), TimeZone.of("Asia/Kolkata"))
        val h = harness("g1", clock = lateClock)
        h.vm.onAction(GoalDetailAction.TickMilestone("m3"))
        assertEquals(listOf("m3" to listOf<MilestoneChange>(MilestoneChange.ReachedOn("2026-11-01"))), h.goals.milestoneChanges)
        assertEquals("Milestone reached · ₹75,000", h.snacks.last())
        assertTrue(h.state.milestones.first { it.id == "m3" }.reached)

        // Un-reaching from the list says nothing; from the sheet it says "Milestone reopened" and closes the sheet.
        h.vm.onAction(GoalDetailAction.TickMilestone("m3"))
        assertEquals(MilestoneChange.ReachedOn(null), h.goals.milestoneChanges.last().second.single())
        assertEquals(1, h.snacks.size)
        h.vm.onAction(GoalDetailAction.OpenMilestone("m2"))
        assertEquals("m2", h.state.milestoneDetail?.id)
        h.vm.onAction(GoalDetailAction.TickMilestone("m2", fromSheet = true))
        assertEquals("Milestone reopened", h.snacks.last())
        assertNull(h.state.milestoneDetail)
    }

    @Test fun milestoneRowsShowDoneNowAndToGo() = runTest {
        val rows = harness("g1").state.milestones
        assertEquals("Reached Sat 20 Jun", rows[0].sub)
        assertEquals("Reached Mon 12 Oct", rows[1].sub)
        assertEquals("Target 14 Jan 2027 · ₹17,000 to go", rows[2].sub)
        assertEquals("No date · ₹42,000 to go", rows[3].sub)
        assertEquals(listOf(false, false, true, false), rows.map { it.now })
    }

    @Test fun chartSeriesAndLabels() = runTest {
        val s = harness("g1").state
        assertEquals(listOf(42000.0, 58000.0), s.chartValues)
        assertEquals(listOf("Aug", "Oct"), s.chartLabels)
        assertEquals(100000.0, s.chartGoal)
        assertEquals("Saved (₹)", s.chartLegend)
        assertEquals("Last logged Today · “bonus”", s.lastLogged)

        val empty = harness("gh", goals = listOf(GoalDetailFixtures.fitness())).state
        assertEquals(listOf(0.0, 0.0), empty.chartValues)
        assertEquals(listOf("Start", "Now"), empty.chartLabels)
        assertNull(empty.lastLogged)

        val ms = harness("gm", goals = listOf(GoalDetailFixtures.marathon())).state
        assertEquals(4.0, ms.chartGoal)
    }

    @Test fun nextTaskIsEarliestOpenLinked() = runTest {
        val tasks = listOf(
            Task("t20", "Review budget", goalId = "g1", due = "2026-10-20"),
            Task("t15", "Transfer ₹8,000", goalId = "g1", due = "2026-10-15"),
            Task("tnone", "Compare FDs", goalId = "g1"),
            Task("tdone", "Open account", goalId = "g1", due = "2026-10-01", done = true),
            Task("other", "Unlinked", due = "2026-10-02"),
        )
        val h = harness("g1", tasks = tasks)
        assertEquals("t15", h.state.nextTask?.id)

        h.vm.onAction(GoalDetailAction.ToggleTask("t15"))
        assertTrue("done:t15:true" in h.tasks.calls)
        assertEquals(UiEvent.Haptic(HapticKind.Confirm), h.events[h.events.size - 2])
        val snack = h.events.last() as UiEvent.Snack<GoalDetailAction>
        assertEquals("Completed · Transfer ₹8,000", snack.text)
        assertEquals(GoalDetailAction.UndoTask(CompletionUndo("t15", null)), snack.undo)
        assertEquals("t20", h.state.nextTask?.id)

        h.vm.onAction(snack.undo!!)
        assertEquals("done:t15:false", h.tasks.calls.last())
        assertEquals("t15", h.state.nextTask?.id)
    }

    @Test fun linkedRowsAndCount() = runTest {
        val tasks = listOf(
            Task("a", "A", goalId = "g1", done = true),
            Task("b", "B", goalId = "g1", due = "2026-10-15"),
            Task("c", "C", goalId = "g1"),
            Task("d", "D", goalId = "g1"),
            Task("e", "E", goalId = "g1"),
            Task("p1", "In project", projectId = "pr"),
        )
        val h = harness(
            "g1",
            tasks = tasks,
            projects = listOf(Project("pr", "Savings", "💰", goalId = "g1")),
            habits = listOf(GoalDetailFixtures.habit("walk", "g1", 21)),
        )
        val linked = h.state.linked
        assertEquals(7, h.state.linkedCount) // 1 project + 5 tasks + 1 habit
        assertEquals("Project · 1 open", linked[0].sub)
        assertEquals(listOf("Task · done", "Task · Tomorrow", "Task · no date", "Task · no date"), linked.drop(1).take(4).map { it.sub })
        assertEquals("Habit · 70% last 30 days", linked.last().sub)

        val none = harness("gm", goals = listOf(GoalDetailFixtures.marathon())).state
        assertEquals(0, none.linkedCount)
        assertEquals("Define the next step", none.nextAction)
    }

    @Test fun blockersCopy() = runTest {
        // Nothing logged and well behind the line since 1 Apr → slipping.
        val slipping = GoalDetailFixtures.fund().copy(current = 10000.0, progressLog = emptyList())
        val s = harness("g1", goals = listOf(slipping)).state
        assertEquals("Slipping", s.status)
        assertEquals("No progress logged in 3+ weeks — the goal is drifting from its target line.", s.blockers)
    }

    @Test fun pauseResumeCompleteAndReopen() = runTest {
        val h = harness("g1")
        h.vm.onAction(GoalDetailAction.TogglePause)
        assertEquals(GoalState.Paused, h.state.state)
        assertEquals("Goal paused · history kept", h.snacks.last())
        h.vm.onAction(GoalDetailAction.TogglePause)
        assertEquals(GoalState.Active, h.state.state)
        assertEquals("Goal resumed", h.snacks.last())
        h.vm.onAction(GoalDetailAction.Complete)
        assertEquals(GoalState.Completed, h.state.state)
        assertEquals("🎉 Goal completed", h.snacks.last())
        h.vm.onAction(GoalDetailAction.Reopen)
        assertEquals(GoalState.Active, h.state.state)
        assertEquals(3, h.snacks.size)
    }

    @Test fun deleteGoalConfirmsAndLeaves() = runTest {
        val h = harness("g1")
        h.vm.onAction(GoalDetailAction.Delete)
        assertTrue("delete:g1" in h.calls)
        assertEquals(listOf(UiEvent.Snack<GoalDetailAction>("Goal deleted"), UiEvent.Close), h.events.takeLast(2))
        assertTrue(h.state.notFound)
    }

    @Test fun formulaIsDebouncedAndBlankClears() = runTest {
        val h = harness("g1")
        h.vm.onAction(GoalDetailAction.Formula("saved"))
        h.vm.onAction(GoalDetailAction.Formula("saved ÷ target"))
        assertEquals("saved ÷ target", h.state.formulaField)
        assertTrue(h.goals.goalChanges.isEmpty())
        advanceTimeBy(601)
        assertEquals(listOf("g1" to listOf<GoalChange>(GoalChange.Formula("saved ÷ target"))), h.goals.goalChanges)
        assertEquals("saved ÷ target", h.state.formulaText)

        h.vm.onAction(GoalDetailAction.Formula("  "))
        h.vm.flushNow()
        runCurrent()
        assertEquals(GoalChange.Formula(null), h.goals.goalChanges.last().second.single())
        assertEquals("₹58,000 ÷ ₹1,00,000", h.state.formulaText)
    }

    @Test fun milestoneFormAddsAndEditsChangedFieldsOnly() = runTest {
        val h = harness("g1")
        h.vm.onAction(GoalDetailAction.EditMilestone(null))
        assertEquals(true, h.state.milestoneForm?.showTarget)
        h.vm.onAction(GoalDetailAction.SaveMilestone(null, "  ", "", null, ""))
        assertTrue(h.calls.none { it.startsWith("addMilestone") })
        h.vm.onAction(GoalDetailAction.SaveMilestone(null, "₹90,000", "90000", "2027-03-01", ""))
        assertTrue("addMilestone:g1" in h.calls)
        assertEquals("Milestone added", h.snacks.last())
        assertNull(h.state.milestoneForm)
        assertEquals("₹90,000", h.state.milestones.last().title)

        h.vm.onAction(GoalDetailAction.EditMilestone("m3"))
        val form = h.state.milestoneForm!!
        assertEquals("75000", form.target)
        assertEquals("2027-01-14", form.date)
        h.vm.onAction(GoalDetailAction.SaveMilestone("m3", form.title, form.target, null, "after bonus"))
        assertEquals(
            "m3" to listOf(MilestoneChange.TargetDate(null), MilestoneChange.Notes("after bonus")),
            h.goals.milestoneChanges.last(),
        )
        assertEquals("Milestone updated", h.snacks.last())
    }

    @Test fun milestoneNotesDebouncedAndDeleteClosesSheet() = runTest {
        val h = harness("g1")
        h.vm.onAction(GoalDetailAction.OpenMilestone("m3"))
        h.vm.onAction(GoalDetailAction.MilestoneNotes("m3", "Bonus in Dec"))
        assertEquals("Bonus in Dec", h.state.milestoneDetail?.notes)
        advanceTimeBy(601)
        assertEquals("m3" to listOf<MilestoneChange>(MilestoneChange.Notes("Bonus in Dec")), h.goals.milestoneChanges.last())

        h.vm.onAction(GoalDetailAction.DeleteMilestone("m3"))
        assertTrue("deleteMilestone:m3" in h.calls)
        assertNull(h.state.milestoneDetail)
        assertTrue(h.state.milestones.none { it.id == "m3" })
    }

    @Test fun unknownGoalIsNotFound() = runTest {
        val s = harness("nope").state
        assertTrue(s.loaded)
        assertTrue(s.notFound)
    }
}
