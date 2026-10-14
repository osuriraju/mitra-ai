package tech.beyondscale.mitra.domain.goals

import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalDraft
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.MilestoneChange
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.ReviewEvery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalMathTest {
    private fun ids(): () -> String { var n = 0; return { "ms${++n}" } }

    private val draft = GoalDraft(
        name = "Emergency fund", type = GoalType.Numeric, due = d(180), current = 0.0, target = 100000.0, unit = "₹",
        milestoneTitles = listOf("25%", "50%", "75%", "Done"),
    )

    @Test fun newGoalSpreadsMilestones() {
        val g = newGoal("g1", draft, TODAY, "2026-10-14T09:00:00Z", ids())
        assertEquals(listOf(25000.0, 50000.0, 75000.0, 100000.0), g.milestones.map { it.target })
        assertEquals(listOf(d(45), d(90), d(135), d(180)), g.milestones.map { it.targetDate })
        assertEquals(listOf("ms1", "ms2", "ms3", "ms4"), g.milestones.map { it.id })
        assertEquals(listOf("25%", "50%", "75%", "Done"), g.milestones.map { it.title })
        assertTrue(g.milestones.all { it.reachedOn == null && it.notes == null })
        assertEquals("2026-11-13", g.reviewOn)
        assertEquals(TODAY, g.startedOn)
        assertEquals(GoalState.Active, g.state)
        assertEquals(100000.0, g.target)
        assertEquals(0.0, g.current)
        assertEquals("2026-10-14T09:00:00Z", g.createdAt)
        assertEquals(emptyList(), g.progressLog)
        assertNull(g.formula)

        val ms = newGoal("g2", draft.copy(type = GoalType.Milestone, current = 5.0), TODAY, "", ids())
        assertEquals(4.0, ms.target)
        assertEquals(0.0, ms.current)
        assertTrue(ms.milestones.all { it.target == null })
        assertEquals(listOf(d(45), d(90), d(135), d(180)), ms.milestones.map { it.targetDate })
    }

    @Test fun newGoalRoundsLikeTheWebForm() {
        val g = newGoal("g", draft.copy(target = 10.0, due = d(100), milestoneTitles = listOf(" a ", "", "b", "c"), reviewEvery = ReviewEvery.Weekly), TODAY, "", ids())
        assertEquals(listOf("a", "b", "c"), g.milestones.map { it.title }) // trimmed, blanks dropped
        assertEquals(listOf(3.0, 7.0, 10.0), g.milestones.map { it.target }) // Math.round(3.33), Math.round(6.67)
        assertEquals(listOf(d(33), d(67), d(100)), g.milestones.map { it.targetDate })
        assertEquals(d(7), g.reviewOn)
        assertEquals(d(90), newGoal("q", draft.copy(reviewEvery = ReviewEvery.Quarterly), TODAY, "", ids()).reviewOn)
        assertEquals(100.0, newGoal("z", draft.copy(target = 0.0, milestoneTitles = emptyList()), TODAY, "", ids()).target) // `+target || 100`
    }

    private val fund = goal(
        current = 50000.0, target = 100000.0, unit = "₹",
        milestones = listOf(Milestone("a", "₹25,000", 25000.0, reachedOn = "2026-03-20"), Milestone("b", "₹75,000", 75000.0), Milestone("c", "₹1,00,000", 100000.0)),
        progressLog = listOf(ProgressEntry("2026-09-01", 50000.0)),
    )

    @Test fun numericProgressAddsAndAutoTicks() {
        val g = fund.applyProgress(30000.0, "bonus", TODAY)
        assertEquals(80000.0, g.current)
        assertEquals(listOf("2026-03-20", TODAY, null), g.milestones.map { it.reachedOn })
        assertEquals(ProgressEntry(TODAY, 80000.0, "bonus"), g.progressLog.last())
        assertEquals(2, g.progressLog.size)
        assertEquals(GoalState.Active, g.state)
        // an already-reached milestone keeps its date
        assertEquals("2026-03-20", g.applyProgress(1.0, null, "2026-10-15").milestones[0].reachedOn)
        // the auto-tick is >= (exactly the milestone's target ticks it)
        assertEquals(TODAY, fund.applyProgress(25000.0, null, TODAY).milestones[1].reachedOn)
    }

    @Test fun reachingTargetCompletesOnlyActive() {
        val done = fund.applyProgress(30000.0, "bonus", TODAY).applyProgress(20000.0, null, "2026-10-15")
        assertEquals(100000.0, done.current)
        assertEquals(GoalState.Completed, done.state)
        assertEquals("2026-10-15", done.milestones[2].reachedOn)
        val paused = fund.copy(state = GoalState.Paused).applyProgress(60000.0, null, TODAY)
        assertEquals(GoalState.Paused, paused.state)
        assertEquals(110000.0, paused.current)
        // habit-type goals never complete from a logged value
        assertEquals(GoalState.Active, goal(type = GoalType.Habit).applyProgress(500.0, null, TODAY).state)
    }

    @Test fun dateGoalProgressSetsValue() {
        val g = goal(type = GoalType.Date, current = 10.0, target = 100.0).applyProgress(4.0, null, TODAY)
        assertEquals(4.0, g.current)
        assertEquals(ProgressEntry(TODAY, 4.0, null), g.progressLog.last())
        assertEquals(GoalState.Completed, goal(type = GoalType.Date, target = 100.0).applyProgress(100.0, null, TODAY).state)
    }

    @Test fun milestoneTypeDerivesCurrentAndTarget() {
        val ms = listOf(Milestone("m1", "First"), Milestone("m2", "Second"), Milestone("m3", "Third"))
        val g = goal(type = GoalType.Milestone, current = 7.0, target = 9.0, milestones = ms).derived()
        assertEquals(0.0, g.current)
        assertEquals(3.0, g.target)

        val reached = g.applyMilestone("m1", listOf(MilestoneChange.ReachedOn(TODAY)))
        assertEquals(1.0, reached.current)
        assertEquals(3.0, reached.target)
        assertEquals(listOf(ProgressEntry(TODAY, 1.0, "First")), reached.progressLog)

        val unreached = reached.applyMilestone("m1", listOf(MilestoneChange.ReachedOn(null)))
        assertEquals(0.0, unreached.current)
        assertEquals(reached.progressLog, unreached.progressLog)

        // the server notes the milestone's title from before the patch
        val renamed = g.applyMilestone("m2", listOf(MilestoneChange.Title("Renamed"), MilestoneChange.ReachedOn("2026-10-10")))
        assertEquals(ProgressEntry("2026-10-10", 1.0, "Second"), renamed.progressLog.single())
        assertEquals("Renamed", renamed.milestones[1].title)

        // re-dating an already-reached milestone appends nothing
        assertEquals(reached.progressLog, reached.applyMilestone("m1", listOf(MilestoneChange.ReachedOn("2026-10-01"))).progressLog)

        // a numeric goal reaching a milestone gets no entry
        val numeric = fund.applyMilestone("b", listOf(MilestoneChange.ReachedOn(TODAY)))
        assertEquals(fund.progressLog, numeric.progressLog)
        assertEquals(50000.0, numeric.current)

        // adding / removing milestones re-derives the counts
        val added = reached.withMilestone(Milestone("m4", "Fourth", reachedOn = TODAY))
        assertEquals(2.0, added.current)
        assertEquals(4.0, added.target)
        assertEquals(reached.progressLog, added.progressLog)
        val removed = added.withoutMilestone("m1")
        assertEquals(1.0, removed.current)
        assertEquals(3.0, removed.target)

        // unknown milestone id → unchanged
        assertEquals(reached, reached.applyMilestone("nope", listOf(MilestoneChange.ReachedOn(TODAY))))
    }

    @Test fun progressEntriesStayInDateOrder() {
        val ms = listOf(Milestone("m1", "First"), Milestone("m2", "Second"))
        val g = goal(type = GoalType.Milestone, milestones = ms, progressLog = listOf(ProgressEntry("2026-09-01", 1.0, "a"), ProgressEntry("2026-10-01", 1.0, "b"))).derived()
        // a back-dated reach lands between the existing entries, like the server's `date asc` order
        val back = g.applyMilestone("m1", listOf(MilestoneChange.ReachedOn("2026-09-15")))
        assertEquals(listOf("2026-09-01", "2026-09-15", "2026-10-01"), back.progressLog.map { it.date })
        // equal dates keep insertion order (new entry after the existing one)
        val same = g.applyMilestone("m2", listOf(MilestoneChange.ReachedOn("2026-09-01")))
        assertEquals(listOf("a", "Second", "b"), same.progressLog.map { it.note })
        // the oldest date goes first
        assertEquals("2026-08-01", g.applyMilestone("m1", listOf(MilestoneChange.ReachedOn("2026-08-01"))).progressLog.first().date)
        // progress dated before the last entry is sorted in too
        val logged = fund.copy(progressLog = listOf(ProgressEntry("2026-09-01", 1.0), ProgressEntry("2026-11-01", 2.0))).applyProgress(5.0, null, TODAY)
        assertEquals(listOf("2026-09-01", TODAY, "2026-11-01"), logged.progressLog.map { it.date })
    }

    @Test fun milestoneTypeProgressStoresValueButShowsCount() {
        val g = goal(type = GoalType.Milestone, milestones = listOf(Milestone("m1", "A", reachedOn = "2026-09-01"), Milestone("m2", "B"))).derived()
        val logged = g.applyProgress(9.0, "note", TODAY)
        assertEquals(1.0, logged.current)
        assertEquals(2.0, logged.target)
        assertEquals(ProgressEntry(TODAY, 9.0, "note"), logged.progressLog.last())
        assertEquals(GoalState.Active, logged.state)
    }

    @Test fun applyChangesPatchesFieldsAndRederives() {
        val g = fund.applyChanges(
            listOf(
                GoalChange.Name("Fund"), GoalChange.Current(1.0), GoalChange.Target(2.0), GoalChange.Formula("x"), GoalChange.State(GoalState.Paused),
                GoalChange.ReviewOn(d(30)), GoalChange.ReviewEvery(ReviewEvery.Weekly), GoalChange.Notes("n"), GoalChange.NextAction("next"),
                GoalChange.Unit(""), GoalChange.Due("2027-01-01"),
            ),
        )
        assertEquals("Fund", g.name)
        assertEquals(1.0, g.current)
        assertEquals(2.0, g.target)
        assertEquals("x", g.formula)
        assertEquals(GoalState.Paused, g.state)
        assertEquals(d(30), g.reviewOn)
        assertEquals(ReviewEvery.Weekly, g.reviewEvery)
        assertEquals("n", g.notes)
        assertEquals("next", g.nextAction)
        assertEquals("", g.unit)
        assertEquals("2027-01-01", g.due)
        assertNull(g.applyChanges(listOf(GoalChange.Formula(null))).formula)
        val asMilestone = fund.applyChanges(listOf(GoalChange.Type(GoalType.Milestone)))
        assertEquals(1.0, asMilestone.current)
        assertEquals(3.0, asMilestone.target)
    }
}
