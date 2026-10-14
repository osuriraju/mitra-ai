package tech.beyondscale.mitra.feature.goals

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.domain.goals.applyChanges
import tech.beyondscale.mitra.domain.goals.goalProgress
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.ReviewEvery
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeGoalsRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun testGoal(
    id: String,
    name: String = id,
    type: GoalType = GoalType.Numeric,
    current: Double = 10.0,
    target: Double = 100.0,
    state: GoalState = GoalState.Active,
) = Goal(
    id = id, name = name, type = type, term = GoalTerm.Medium, due = "2027-03-31", startedOn = "2026-10-01",
    current = current, target = target, unit = "", formula = null, nextAction = "", reviewEvery = ReviewEvery.Monthly,
    reviewOn = "2026-11-14", state = state, notes = "", createdAt = "", milestones = emptyList(), progressLog = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class GoalEditorViewModelTest : ViewModelTest() {
    // 08:00Z = 13:30 on Wed 14 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-14T08:00:00Z"), TimeZone.of("Asia/Kolkata"))

    /** Records the exact change lists the editor sends (the shared fake records only the goal id). */
    private class Recording(val fake: FakeGoalsRepository) : GoalsRepository by fake {
        val updates = mutableListOf<Pair<String, List<GoalChange>>>()
        /** While set, reading [goals] suspends until it completes (a slow Edit lookup). */
        var gate: CompletableDeferred<Unit>? = null
        override val goals: Flow<List<Goal>> = flow {
            gate?.await()
            emitAll(fake.goals)
        }
        override suspend fun update(id: String, changes: List<GoalChange>) {
            updates += id to changes
            fake.update(id, changes)
        }
    }

    private class Harness(
        val vm: GoalEditorViewModel,
        val repo: Recording,
        val events: List<UiEvent<GoalEditorAction>>,
        val nav: List<GoalEditorEvent>,
    ) {
        val state get() = vm.state.value
        val snacks get() = events.filterIsInstance<UiEvent.Snack<GoalEditorAction>>().map { it.text }
    }

    private fun TestScope.start(goals: List<Goal> = emptyList()): Harness {
        val repo = Recording(FakeGoalsRepository(goals, clock))
        val vm = GoalEditorViewModel(repo, clock)
        val events = mutableListOf<UiEvent<GoalEditorAction>>()
        val nav = mutableListOf<GoalEditorEvent>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        backgroundScope.launch(d) { vm.navigation.toList(nav) }
        runCurrent()
        return Harness(vm, repo, events, nav)
    }

    @Test fun openPrefillsWebDefaults() = runTest {
        val h = start()
        h.vm.onAction(GoalEditorAction.Open)
        runCurrent()
        val f = h.state.form
        assertTrue(f.open)
        assertEquals("New goal", h.state.heading)
        assertEquals("Create goal", h.state.saveLabel)
        assertEquals(GoalType.Numeric, f.type)
        assertEquals(GoalTerm.Medium, f.term)
        assertEquals("2027-04-12", f.due) // today + 180
        assertEquals("100", f.target)
        assertEquals("0", f.current)
        assertEquals(ReviewEvery.Monthly, f.review)
        assertEquals("25%\n50%\n75%\nDone", f.milestones)
        assertTrue(h.state.showNumbers)
        assertTrue(h.state.showUnit)
        assertTrue(h.state.showMilestones)
        assertEquals("Track a number toward a target (money, books, kg).", h.state.typeHint)
    }

    @Test fun numbersAreSanitisedToDecimals() = runTest {
        val h = start()
        h.vm.onAction(GoalEditorAction.Open)
        h.vm.onAction(GoalEditorAction.Target("1,00,000.5x"))
        h.vm.onAction(GoalEditorAction.Current("₹2 000"))
        assertEquals("100000.5", h.state.form.target)
        assertEquals("2000", h.state.form.current)
        // A second dot is refused while typing.
        h.vm.onAction(GoalEditorAction.Target("100000.5."))
        assertEquals("100000.5", h.state.form.target)
        // So is anything above the server's 1e12 cap.
        h.vm.onAction(GoalEditorAction.Current("1000000000000"))
        assertEquals("1000000000000", h.state.form.current)
        h.vm.onAction(GoalEditorAction.Current("10000000000000"))
        assertEquals("1000000000000", h.state.form.current)
    }

    @Test fun textFieldsAreCappedLikeTheServer() = runTest {
        val h = start()
        h.vm.onAction(GoalEditorAction.Open)
        h.vm.onAction(GoalEditorAction.Name("n".repeat(200)))
        assertEquals(160, h.state.form.name.length)
        h.vm.onAction(GoalEditorAction.NextAction("a".repeat(250)))
        assertEquals(200, h.state.form.nextAction.length)
        h.vm.onAction(GoalEditorAction.Milestones("m".repeat(130) + "\nShort"))
        assertEquals(listOf(120, 5), h.state.form.milestones.split('\n').map { it.length })
    }

    @Test fun moreThanFiftyMilestonesIsBlocked() = runTest {
        val h = start()
        h.vm.onAction(GoalEditorAction.Open)
        h.vm.onAction(GoalEditorAction.Name("Big"))
        h.vm.onAction(GoalEditorAction.Milestones((1..51).joinToString("\n") { "M$it" }))
        h.vm.onAction(GoalEditorAction.Save)
        runCurrent()
        assertEquals("Up to 50 milestones", h.state.form.milestonesError)
        assertTrue(h.state.form.open)
        assertEquals(emptyList(), h.repo.fake.calls)
        h.vm.onAction(GoalEditorAction.Milestones((1..50).joinToString("\n") { "M$it" }))
        assertEquals(null, h.state.form.milestonesError)
        h.vm.onAction(GoalEditorAction.Save)
        runCurrent()
        assertEquals(50, h.repo.fake.goalFlow.value.single().milestones.size)
    }

    @Test fun createSpreadsMilestones() = runTest {
        val h = start()
        h.vm.onAction(GoalEditorAction.Open)
        h.vm.onAction(GoalEditorAction.Name("  Run a half marathon "))
        h.vm.onAction(GoalEditorAction.Save)
        runCurrent()
        val g = h.repo.fake.goalFlow.value.single()
        assertEquals("Run a half marathon", g.name)
        assertEquals(listOf(25.0, 50.0, 75.0, 100.0), g.milestones.map { it.target })
        assertEquals(listOf("25%", "50%", "75%", "Done"), g.milestones.map { it.title })
        assertEquals("2027-04-12", g.milestones.last().targetDate)
        assertEquals(100.0, g.target)
        assertEquals(0.0, g.current)
        assertEquals("2026-11-13", g.reviewOn) // today + 30
        assertEquals(listOf("Goal set · Run a half marathon"), h.snacks)
        assertEquals(listOf<GoalEditorEvent>(GoalEditorEvent.Created(g.id)), h.nav)
        assertFalse(h.state.form.open)
    }

    @Test fun emptyOrInvalidNumbersFallBack() = runTest {
        val h = start()
        h.vm.onAction(GoalEditorAction.Open)
        h.vm.onAction(GoalEditorAction.Name("Books"))
        h.vm.onAction(GoalEditorAction.Target(""))
        h.vm.onAction(GoalEditorAction.Current("1.2.3"))
        h.vm.onAction(GoalEditorAction.Unit(rupees = true))
        h.vm.onAction(GoalEditorAction.Milestones(""))
        h.vm.onAction(GoalEditorAction.Save)
        runCurrent()
        val g = h.repo.fake.goalFlow.value.single()
        assertEquals(100.0, g.target)
        assertEquals(0.0, g.current)
        assertEquals("₹", g.unit)
        assertEquals(emptyList(), g.milestones)
    }

    @Test fun milestoneTypeHidesNumbers() = runTest {
        val h = start()
        h.vm.onAction(GoalEditorAction.Open)
        h.vm.onAction(GoalEditorAction.Type(GoalType.Milestone))
        assertFalse(h.state.showNumbers)
        assertFalse(h.state.showUnit)
        assertEquals("Progress = milestones reached ÷ total.", h.state.typeHint)
        h.vm.onAction(GoalEditorAction.Name("Launch the app"))
        h.vm.onAction(GoalEditorAction.Milestones("Beta\n\nLaunch\n Press "))
        h.vm.onAction(GoalEditorAction.Save)
        runCurrent()
        val g = h.repo.fake.goalFlow.value.single()
        assertEquals(3.0, g.target)
        assertEquals(0.0, g.current)
        assertEquals(listOf(null, null, null), g.milestones.map { it.target })

        h.vm.onAction(GoalEditorAction.Type(GoalType.Habit))
        assertFalse(h.state.showNumbers)
        h.vm.onAction(GoalEditorAction.Type(GoalType.Date))
        assertTrue(h.state.showNumbers)
        assertFalse(h.state.showUnit)
    }

    @Test fun editSendsOnlyChanges() = runTest {
        val g = testGoal("g1", name = "Emergency fund", current = 58000.0, target = 100000.0)
        val h = start(listOf(g))
        h.vm.onAction(GoalEditorAction.Edit("g1"))
        runCurrent()
        assertEquals("Edit goal", h.state.heading)
        assertEquals("Save", h.state.saveLabel)
        assertEquals("58000", h.state.form.current)
        assertEquals("100000", h.state.form.target)
        assertFalse(h.state.showMilestones)
        h.vm.onAction(GoalEditorAction.Review(ReviewEvery.Weekly))
        h.vm.onAction(GoalEditorAction.Save)
        runCurrent()
        assertEquals(listOf("g1" to listOf<GoalChange>(GoalChange.ReviewEvery(ReviewEvery.Weekly))), h.repo.updates)
        assertEquals(listOf("Goal updated"), h.snacks)
        assertEquals(emptyList(), h.nav)
        assertFalse(h.state.form.open)
    }

    @Test fun editOfMilestoneGoalNeverSendsNumbers() = runTest {
        val ms = listOf(Milestone("m1", "Beta", reachedOn = "2026-10-01"), Milestone("m2", "Launch"))
        val g = testGoal("g1", type = GoalType.Milestone, current = 1.0, target = 2.0).copy(milestones = ms)
        val h = start(listOf(g))
        h.vm.onAction(GoalEditorAction.Edit("g1"))
        runCurrent()
        h.vm.onAction(GoalEditorAction.Name("Ship it"))
        h.vm.onAction(GoalEditorAction.Target("50"))
        h.vm.onAction(GoalEditorAction.Due("2027-01-31"))
        h.vm.onAction(GoalEditorAction.Term(GoalTerm.Short))
        h.vm.onAction(GoalEditorAction.NextAction("  Write notes "))
        h.vm.onAction(GoalEditorAction.Save)
        runCurrent()
        assertEquals(
            listOf<GoalChange>(GoalChange.Name("Ship it"), GoalChange.Term(GoalTerm.Short), GoalChange.Due("2027-01-31"), GoalChange.NextAction("Write notes")),
            h.repo.updates.single().second,
        )
    }

    @Test fun editNumbersAndTypeSendsParsedValues() = runTest {
        val h = start(listOf(testGoal("g1", current = 10.0, target = 100.0)))
        h.vm.onAction(GoalEditorAction.Edit("g1"))
        runCurrent()
        h.vm.onAction(GoalEditorAction.Type(GoalType.Date))
        h.vm.onAction(GoalEditorAction.Current("12.5"))
        h.vm.onAction(GoalEditorAction.Target("100.0"))
        h.vm.onAction(GoalEditorAction.Unit(rupees = true))
        h.vm.onAction(GoalEditorAction.Save)
        runCurrent()
        // A type change always sends both numbers (web GoalForm.save), even the unchanged target.
        assertEquals(
            listOf<GoalChange>(GoalChange.Type(GoalType.Date), GoalChange.Unit("₹"), GoalChange.Current(12.5), GoalChange.Target(100.0)),
            h.repo.updates.single().second,
        )
    }

    @Test fun editMilestoneGoalToNumericSendsNumbers() = runTest {
        val ms = listOf(
            Milestone("m1", "A", reachedOn = "2026-10-01"), Milestone("m2", "B", reachedOn = "2026-10-05"),
            Milestone("m3", "C"), Milestone("m4", "D"),
        )
        // Derived 2 / 4 on screen; the server's stored row still holds its creation numbers.
        val g = testGoal("g1", type = GoalType.Milestone, current = 2.0, target = 4.0).copy(milestones = ms)
        val h = start(listOf(g))
        h.vm.onAction(GoalEditorAction.Edit("g1"))
        runCurrent()
        assertEquals("2", h.state.form.current)
        assertEquals("4", h.state.form.target)
        h.vm.onAction(GoalEditorAction.Type(GoalType.Numeric))
        h.vm.onAction(GoalEditorAction.Save)
        runCurrent()
        val changes = h.repo.updates.single().second
        assertEquals(listOf<GoalChange>(GoalChange.Type(GoalType.Numeric), GoalChange.Current(2.0), GoalChange.Target(4.0)), changes)
        val local = h.repo.fake.goalFlow.value.single()
        assertEquals(2.0, local.current)
        assertEquals(4.0, local.target)
        assertEquals(50, goalProgress(local, emptyList(), "2026-10-14"))
        // The echo: the server applies the PATCH to its stored row (0 / 100) and returns the same goal.
        val echo = g.copy(current = 0.0, target = 100.0).applyChanges(changes)
        assertEquals(local, echo)
    }

    @Test fun editWithoutChangesSnacksWithoutWriting() = runTest {
        val h = start(listOf(testGoal("g1")))
        h.vm.onAction(GoalEditorAction.Edit("g1"))
        runCurrent()
        h.vm.onAction(GoalEditorAction.Save)
        runCurrent()
        assertEquals(emptyList(), h.repo.updates)
        assertEquals(listOf("Goal updated"), h.snacks)
        assertFalse(h.state.form.open)
    }

    @Test fun lateEditResolutionAfterDismissDoesNotReopen() = runTest {
        val h = start(listOf(testGoal("g1")))
        val gate = CompletableDeferred<Unit>()
        h.repo.gate = gate
        h.vm.onAction(GoalEditorAction.Edit("g1"))
        runCurrent()
        h.vm.onAction(GoalEditorAction.Dismiss)
        gate.complete(Unit)
        runCurrent()
        assertFalse(h.state.form.open)

        // Edit, then Open before the lookup lands: the new-goal form stays.
        val gate2 = CompletableDeferred<Unit>()
        h.repo.gate = gate2
        h.vm.onAction(GoalEditorAction.Edit("g1"))
        runCurrent()
        h.vm.onAction(GoalEditorAction.Open)
        gate2.complete(Unit)
        runCurrent()
        assertTrue(h.state.form.open)
        assertEquals("New goal", h.state.heading)
        assertEquals("", h.state.form.name)
    }

    @Test fun editUnknownGoalStaysClosed() = runTest {
        val h = start()
        h.vm.onAction(GoalEditorAction.Edit("missing"))
        runCurrent()
        assertFalse(h.state.form.open)
    }

    @Test fun blankNameError() = runTest {
        val h = start()
        h.vm.onAction(GoalEditorAction.Open)
        h.vm.onAction(GoalEditorAction.Name("   "))
        h.vm.onAction(GoalEditorAction.Save)
        runCurrent()
        assertEquals("Give the goal a name", h.state.form.error)
        assertTrue(h.state.form.open)
        assertEquals(emptyList(), h.repo.fake.calls)
        h.vm.onAction(GoalEditorAction.Name("R"))
        assertEquals(null, h.state.form.error)
    }

    @Test fun dismissClosesAndReopenResets() = runTest {
        val h = start(listOf(testGoal("g1", state = GoalState.Paused)))
        h.vm.onAction(GoalEditorAction.Edit("g1"))
        runCurrent()
        h.vm.onAction(GoalEditorAction.Dismiss)
        assertFalse(h.state.form.open)
        h.vm.onAction(GoalEditorAction.Open)
        assertEquals("", h.state.form.name)
        assertEquals("New goal", h.state.heading)
    }
}
