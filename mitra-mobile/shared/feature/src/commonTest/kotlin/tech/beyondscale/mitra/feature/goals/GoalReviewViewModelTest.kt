package tech.beyondscale.mitra.feature.goals

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.domain.goals.StatusTone
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.ReviewEvery
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeGoalsRepository
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GoalReviewViewModelTest : ViewModelTest() {
    // 10:00 on Wed 14 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-14T04:30:00Z"), TimeZone.of("Asia/Kolkata"))
    private val today = "2026-10-14"
    private fun d(n: Int) = addDays(today, n)

    /** Started 2026-07-16, due 2027-01-12: half the time has elapsed, so the expected line is 50 %. */
    private fun goal(
        id: String,
        current: Double = 50.0,
        target: Double = 100.0,
        type: GoalType = GoalType.Numeric,
        state: GoalState = GoalState.Active,
        review: ReviewEvery = ReviewEvery.Monthly,
        log: List<ProgressEntry> = listOf(ProgressEntry(d(-3), current)),
        milestones: List<Milestone> = emptyList(),
    ) = Goal(
        id, "Goal $id", type, GoalTerm.Medium, "2027-01-12", "2026-07-16", current, target, "", null, "", review, today, state, "",
        "2026-07-16T00:00:00.000Z", milestones, log,
    )

    /** Records every `update` with its changes, then applies it through the fake. */
    private class Recording(val fake: FakeGoalsRepository) : GoalsRepository by fake {
        val updates = mutableListOf<Pair<String, List<GoalChange>>>()
        override suspend fun update(id: String, changes: List<GoalChange>) {
            updates += id to changes
            fake.update(id, changes)
        }
    }

    private class Harness(val vm: GoalReviewViewModel, val repo: Recording, val events: List<UiEvent<GoalReviewAction>>) {
        val state get() = vm.state.value
        val snacks get() = events.filterIsInstance<UiEvent.Snack<GoalReviewAction>>().map { it.text }
    }

    private fun TestScope.harness(vararg goals: Goal): Harness {
        val repo = Recording(FakeGoalsRepository(goals.toList(), clock))
        val vm = GoalReviewViewModel(repo, FakeHabitsRepository(), clock)
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        val events = mutableListOf<UiEvent<GoalReviewAction>>()
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, repo, events)
    }

    @Test fun trajectoryCounts() = runTest {
        val h = harness(
            goal("on", current = 45.0),
            goal("att1", current = 44.0),
            goal("att2", current = 40.0),
            goal("behind", current = 34.0),
            goal("slip", current = 39.0, log = emptyList()),
            goal("done", current = 100.0, state = GoalState.Completed),
        )
        val s = h.state
        assertEquals(1, s.onTrack)
        assertEquals(3, s.behind)
        assertEquals(1, s.slipping)
        assertEquals(listOf("on", "att1", "att2", "behind", "slip"), s.goals.map { it.id })
        assertEquals("Monthly · Wed 14 Oct · 5 goals", s.sub)
        assertEquals(StatusTone.Accent, s.goals[0].ringTone)
        assertEquals(StatusTone.Danger, s.goals[4].ringTone)
        assertEquals("45%", s.goals[0].ringLabel)
    }

    @Test fun trajectoryCountsOnePerBucket() = runTest {
        // the plan's case: on-track, attention, behind, slipping → 1 / 2 / 1
        val s = harness(goal("a", current = 45.0), goal("b", current = 44.0), goal("c", current = 34.0), goal("d", current = 39.0, log = emptyList())).state
        assertEquals(Triple(1, 2, 1), Triple(s.onTrack, s.behind, s.slipping))
    }

    @Test fun deltaNumericAndFallback() = runTest {
        val numeric = goal(
            "n", current = 52000.0, target = 100000.0,
            log = listOf(ProgressEntry(d(-40), 40000.0), ProgressEntry(d(-5), 52000.0)),
        )
        val ms = goal(
            "m", type = GoalType.Milestone, current = 1.0, target = 4.0, log = emptyList(),
            milestones = listOf(Milestone("a", "A", reachedOn = d(-50)), Milestone("b", "B"), Milestone("c", "C"), Milestone("d", "D")),
        )
        val old = goal("o", current = 30.0, log = listOf(ProgressEntry(d(-40), 30.0)))
        val s = harness(numeric, ms, old).state
        assertEquals("+12 pts this month", reviewDelta(numeric, emptyList(), today))
        assertEquals("+12 pts this month · on track", s.goals[0].line)
        assertEquals("25% · nothing logged yet", reviewDelta(ms, emptyList(), today))
        assertTrue(s.goals[1].line.startsWith("25% · nothing logged yet · "))
        // numeric without a log in the last 31 days falls back to the progress line
        assertEquals("30% · last logged Fri 4 Sep", reviewDelta(old, emptyList(), today))
        // only recent logs → the first recent one is the baseline; losses show without a plus
        val down = goal("x", current = 40.0, target = 100.0, log = listOf(ProgressEntry(d(-10), 50.0), ProgressEntry(d(-1), 40.0)))
        assertEquals("-10 pts this month", reviewDelta(down, emptyList(), today))
        val recent = goal("y", type = GoalType.Date, log = listOf(ProgressEntry(today, 3.0)))
        assertEquals("50% · last logged Today", reviewDelta(recent, emptyList(), today))
    }

    @Test fun pauseKeepsPageAndSkipsReviewDate() = runTest {
        val h = harness(goal("A"), goal("B"), goal("C", review = ReviewEvery.Weekly))
        h.vm.onAction(GoalReviewAction.Decide("B", Decision.Pause))
        runCurrent()
        assertEquals(listOf("B" to listOf<GoalChange>(GoalChange.State(GoalState.Paused))), h.repo.updates)
        assertEquals(listOf("A", "B", "C"), h.state.goals.map { it.id })
        assertEquals(Decision.Pause, h.state.goals[1].decision)
        assertEquals(1, h.state.decided)
        h.vm.onAction(GoalReviewAction.Finish)
        runCurrent()
        assertEquals(
            listOf(
                "A" to listOf<GoalChange>(GoalChange.ReviewOn("2026-11-13")),
                "C" to listOf<GoalChange>(GoalChange.ReviewOn("2026-10-21")),
            ),
            h.repo.updates.drop(1),
        )
        assertEquals(listOf("Review done · 1 decision recorded"), h.snacks)
        assertTrue(UiEvent.Close in h.events)
    }

    @Test fun keepRecordsDecisionWithoutWriting() = runTest {
        val h = harness(goal("A"))
        h.vm.onAction(GoalReviewAction.Decide("A", Decision.Keep))
        runCurrent()
        assertEquals(Decision.Keep, h.state.goals[0].decision)
        assertEquals(emptyList(), h.repo.updates)
        assertEquals(listOf("Goal A" to "Keep"), h.state.summary)
    }

    @Test fun adjustSendsTargetAndDue() = runTest {
        val h = harness(goal("A", current = 50000.0, target = 100000.0))
        h.vm.onAction(GoalReviewAction.StartAdjust("A"))
        runCurrent()
        assertEquals("A", h.state.adjusting)
        h.vm.onAction(GoalReviewAction.AdjustTarget("1,20,000x"))
        assertEquals("120000", h.state.adjustTarget)
        h.vm.onAction(GoalReviewAction.AdjustDue("A", "2027-06-30"))
        h.vm.onAction(GoalReviewAction.SaveAdjust("A"))
        runCurrent()
        assertEquals(
            listOf(
                "A" to listOf<GoalChange>(GoalChange.Due("2027-06-30")),
                "A" to listOf<GoalChange>(GoalChange.Target(120000.0)),
            ),
            h.repo.updates,
        )
        assertEquals(Decision.Adjusted, h.state.goals[0].decision)
        assertEquals(null, h.state.adjusting)
        assertEquals("2027-06-30", h.state.goals[0].due)
    }

    @Test fun adjustWithoutTargetOnlyRecordsDecision() = runTest {
        val h = harness(goal("A"))
        h.vm.onAction(GoalReviewAction.StartAdjust("A"))
        h.vm.onAction(GoalReviewAction.SaveAdjust("A"))
        runCurrent()
        assertEquals(emptyList(), h.repo.updates)
        assertEquals(Decision.Adjusted, h.state.goals[0].decision)
    }

    @Test fun finishSnackCountsDecisions() = runTest {
        val two = harness(goal("A"), goal("B"))
        two.vm.onAction(GoalReviewAction.Decide("A", Decision.Keep))
        two.vm.onAction(GoalReviewAction.Decide("B", Decision.Keep))
        two.vm.onAction(GoalReviewAction.Finish)
        runCurrent()
        assertEquals(listOf("Review done · 2 decisions recorded"), two.snacks)

        val one = harness(goal("A"), goal("B"))
        one.vm.onAction(GoalReviewAction.Decide("A", Decision.Keep))
        one.vm.onAction(GoalReviewAction.Decide("A", Decision.Keep))
        one.vm.onAction(GoalReviewAction.Finish)
        one.vm.onAction(GoalReviewAction.Finish)
        runCurrent()
        assertEquals(listOf("Review done · 1 decision recorded"), one.snacks)
    }

    @Test fun emptyReviewCanStillFinish() = runTest {
        val h = harness(goal("P", state = GoalState.Paused))
        assertTrue(h.state.loaded)
        assertEquals(emptyList(), h.state.goals)
        assertEquals("Monthly · Wed 14 Oct · 0 goals", h.state.sub)
        h.vm.onAction(GoalReviewAction.Finish)
        runCurrent()
        assertEquals(emptyList(), h.repo.updates)
        assertEquals(listOf("Review done · 0 decisions recorded"), h.snacks)
    }
}
