package tech.beyondscale.mitra.domain.goals

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.domain.model.ALL_DAYS
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TimeOfDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalSelectorsTest {
    private fun linkedHabit(id: String, goalId: String, days: List<Boolean>, done: Int): Habit {
        val h = Habit(id, id, HabitType.Binary, 1, "", "repeat", TimeOfDay.Anytime, days, null, goalId, true, false, "", emptyMap())
        val due = (29 downTo 0).map { addDays(TODAY, -it) }.filter { days[tech.beyondscale.mitra.core.dowMon(it)] }
        return h.copy(logs = due.take(done).associateWith { 1 })
    }

    @Test fun milestoneProgressIsReachedOverTotal() {
        val ms = listOf(Milestone("a", "A", reachedOn = "2026-09-01"), Milestone("b", "B"), Milestone("c", "C"), Milestone("d", "D"))
        assertEquals(25, goalProgress(goal(type = GoalType.Milestone, milestones = ms), emptyList(), TODAY))
        assertEquals(0, goalProgress(goal(type = GoalType.Milestone), emptyList(), TODAY))
        assertEquals(40, goalProgress(run10k, emptyList(), TODAY))
    }

    @Test fun dateGoalProgressIsElapsedTime() {
        assertEquals(14, goalProgress(goal(type = GoalType.Date, startedOn = "2026-10-01", due = "2026-12-30"), emptyList(), TODAY))
        assertEquals(100, goalProgress(goal(type = GoalType.Date, startedOn = "2026-01-01", due = "2026-02-01"), emptyList(), TODAY))
        // a goal due the day it started divides by max(1, 0)
        assertEquals(100, goalProgress(goal(type = GoalType.Date, startedOn = "2026-10-13", due = "2026-10-13"), emptyList(), TODAY))
    }

    @Test fun habitGoalAveragesLinked30DayRates() {
        val a = linkedHabit("a", "g-spanish", ALL_DAYS, 24) // 24 / 30 = 80 %
        val b = linkedHabit("b", "g-spanish", listOf(true, true, true, true, false, false, false), 11) // 11 / 18 = 61 %
        val other = linkedHabit("c", "g-other", ALL_DAYS, 0)
        assertEquals(80, tech.beyondscale.mitra.domain.habits.rateDays(a, 30, TODAY).pct)
        assertEquals(61, tech.beyondscale.mitra.domain.habits.rateDays(b, 30, TODAY).pct)
        assertEquals(71, goalProgress(spanish, listOf(a, b, other), TODAY)) // (80 + 61) / 2 = 70.5 → 71
        assertEquals(22, goalProgress(spanish, listOf(other), TODAY)) // nothing linked → current / target
    }

    @Test fun numericCapsAndGuardsZeroTarget() {
        assertEquals(100, goalProgress(goal(current = 150.0, target = 100.0), emptyList(), TODAY))
        assertEquals(0, goalProgress(goal(current = 0.0, target = 0.0), emptyList(), TODAY))
        assertEquals(50, goalProgress(goal(current = 0.5, target = 0.0), emptyList(), TODAY)) // max(1, target)
        assertEquals(62, goalProgress(emergencyFund, emptyList(), TODAY))
        assertEquals(58, goalProgress(books, emptyList(), TODAY)) // 14 / 24 = 58.33
    }

    @Test fun goalStatusThresholds() {
        val fresh = listOf(ProgressEntry(TODAY, 1.0))
        fun st(p: Double, log: List<ProgressEntry> = fresh, state: GoalState = GoalState.Active) =
            goalStatus(goal(current = p, progressLog = log, state = state), emptyList(), TODAY)
        assertEquals(GoalStatus.OnTrack, st(45.0))
        assertEquals(GoalStatus.Attention, st(44.0))
        assertEquals(GoalStatus.Attention, st(35.0))
        assertEquals(GoalStatus.Behind, st(34.0))
        assertEquals(GoalStatus.Slipping, st(39.0, listOf(ProgressEntry(d(-22), 1.0))))
        assertEquals(GoalStatus.Attention, st(39.0, listOf(ProgressEntry(d(-21), 1.0))))
        assertEquals(GoalStatus.Slipping, st(39.0, emptyList()))
        assertEquals(GoalStatus.Attention, st(40.0, emptyList())) // stale but not < expected − 10
        assertEquals(GoalStatus.Paused, st(0.0, emptyList(), GoalState.Paused))
        assertEquals(GoalStatus.Completed, st(0.0, emptyList(), GoalState.Completed))
        // the last entry decides staleness, not the newest date
        assertEquals(GoalStatus.Slipping, st(39.0, listOf(ProgressEntry(TODAY, 1.0), ProgressEntry(d(-30), 2.0))))
        assertEquals("Needs attention", GoalStatus.Attention.label)
    }

    @Test fun tonesFollowTheWeb() {
        assertEquals(StatusTone.Success, statusTone(GoalStatus.OnTrack))
        assertEquals(StatusTone.Warning, statusTone(GoalStatus.Attention))
        assertEquals(StatusTone.Warning, statusTone(GoalStatus.Behind))
        assertEquals(StatusTone.Danger, statusTone(GoalStatus.Slipping))
        assertEquals(StatusTone.Accent, statusTone(GoalStatus.Completed))
        assertEquals(StatusTone.Neutral, statusTone(GoalStatus.Paused))
        assertEquals(StatusTone.Accent, ringTone(GoalStatus.OnTrack))
        assertEquals(StatusTone.Danger, ringTone(GoalStatus.Slipping))
        assertEquals(StatusTone.Warning, ringTone(GoalStatus.Completed))
        assertEquals(StatusTone.Success, cardRingTone(GoalStatus.Completed))
        assertEquals(StatusTone.Accent, cardRingTone(GoalStatus.OnTrack))
        assertEquals(StatusTone.Warning, cardRingTone(GoalStatus.Paused))
        assertEquals(StatusTone.Danger, barTone(GoalStatus.Slipping))
        assertEquals(StatusTone.Accent, barTone(GoalStatus.Completed))
        assertEquals(StatusTone.Warning, barTone(GoalStatus.Behind))
    }

    @Test fun labelsAndMonthsLeft() {
        assertEquals("₹1,00,000", goalTargetLabel(emergencyFund))
        assertEquals("₹62,000", goalValueLabel(emergencyFund))
        assertEquals("12.5", goalValueLabel(goal(current = 12.5)))
        assertEquals("24", goalTargetLabel(books))
        assertEquals("2 months left", monthsLeft(d(45), TODAY))
        assertEquals("30 days left", monthsLeft(d(30), TODAY))
        assertEquals("1 months left", monthsLeft(d(31), TODAY)) // web copy as is
        assertEquals("0 days left", monthsLeft(TODAY, TODAY))
        assertEquals("past due", monthsLeft(d(-1), TODAY))
        assertTrue(reviewDue(goal(reviewOn = TODAY), TODAY))
        assertTrue(reviewDue(goal(reviewOn = d(-6)), TODAY))
        assertFalse(reviewDue(goal(reviewOn = d(1)), TODAY))
        assertFalse(reviewDue(goal(reviewOn = TODAY, state = GoalState.Paused), TODAY))
        assertEquals("Mar 2027", dueMonthLabel(emergencyFund))
    }

    @Test fun linksLineCountsEachKind() {
        val tasks = listOf(Task(id = "t1", title = "A", goalId = "g"), Task(id = "t2", title = "B", goalId = "g"), Task(id = "t3", title = "C", goalId = "x"))
        val habits = listOf(linkedHabit("h", "g", ALL_DAYS, 0))
        val projects = listOf(Project("p1", "P", goalId = "g"), Project("p2", "Q", goalId = "g"))
        assertEquals("2 projects · 2 tasks · 1 habit", linksLine("g", tasks, habits, projects))
        assertEquals("1 task", linksLine("x", tasks, habits, projects))
        assertEquals("Nothing linked yet", linksLine("none", tasks, habits, projects))
    }

    @Test fun formulaAndBlockers() {
        assertEquals(emergencyFund.formula, formulaText(emergencyFund))
        assertEquals("14 ÷ 24", formulaText(books))
        assertEquals("milestones reached ÷ total milestones", formulaText(run10k))
        assertEquals("average 30-day consistency of linked habits", formulaText(spanish))
        assertEquals("time elapsed ÷ total time to due date", formulaText(goal(type = GoalType.Date)))
        assertEquals("No progress logged in 3+ weeks — the goal is drifting from its target line.", blockers(books, GoalStatus.Slipping))
        assertEquals("Progress is below the pace needed to finish by the due date.", blockers(books, GoalStatus.Behind))
        assertEquals("Over-budget categories this month reduce the surplus available to transfer.", blockers(emergencyFund, GoalStatus.Attention))
        assertEquals("", blockers(emergencyFund, GoalStatus.OnTrack))
        assertEquals("", blockers(books, GoalStatus.Attention))
    }
}
