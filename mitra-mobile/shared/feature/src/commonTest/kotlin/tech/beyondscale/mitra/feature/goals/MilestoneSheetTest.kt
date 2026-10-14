package tech.beyondscale.mitra.feature.goals

import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.ProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class MilestoneSheetTest {
    private val today = GoalDetailFixtures.TODAY
    private val fund = GoalDetailFixtures.fund()

    @Test fun paceLine() {
        // (58000 − 42000) / 60 days × 30 = 8000 a month; ceil(17000 / 8000) = 3
        val ui = milestoneDetailUi(fund, fund.milestones[2], emptyList(), null, today)
        assertEquals("₹58,000 of ₹75,000 · on pace at ₹8,000/month (≈ 3 months)", ui.line)
        assertEquals("Remaining", ui.kpiLabel)
        assertEquals("₹17,000", ui.kpiValue)
        assertEquals("14 Jan 2027", ui.targetDate)
        assertEquals(77, ui.pct)
    }

    @Test fun noPaceWithOneEntry() {
        val g = fund.copy(progressLog = listOf(ProgressEntry(today, 58000.0)))
        assertEquals("₹58,000 of ₹75,000", milestoneDetailUi(g, g.milestones[2], emptyList(), null, today).line)
    }

    @Test fun reachedAndUntargetedMilestones() {
        val reached = milestoneDetailUi(fund, fund.milestones[1], emptyList(), null, today)
        assertEquals("Reached Mon 12 Oct", reached.line)
        assertEquals(100, reached.pct)
        assertEquals("₹0", reached.kpiValue)

        val run = GoalDetailFixtures.marathon()
        val open = milestoneDetailUi(run, run.milestones[2], emptyList(), "draft", today)
        assertEquals("Status", open.kpiLabel)
        assertEquals("Open", open.kpiValue)
        assertEquals("—", open.targetDate)
        assertEquals(0, open.pct)
        assertEquals("Tick it when the milestone is done.", open.line)
        assertEquals("draft", open.notes)
        assertEquals(GoalType.Milestone, run.type)
    }
}
