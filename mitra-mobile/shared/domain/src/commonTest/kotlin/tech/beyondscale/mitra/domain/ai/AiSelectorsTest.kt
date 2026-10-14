package tech.beyondscale.mitra.domain.ai

import tech.beyondscale.mitra.domain.model.AiAction
import tech.beyondscale.mitra.domain.model.AiMessage
import tech.beyondscale.mitra.domain.model.AiProposal
import tech.beyondscale.mitra.domain.model.AiWho
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.RescheduleMove
import tech.beyondscale.mitra.domain.model.RevertOp
import tech.beyondscale.mitra.domain.model.Suggestion
import tech.beyondscale.mitra.domain.model.SuggestionState
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TODAY = "2026-10-14"

class AiSelectorsTest {
    @Test fun boldAndLabels() {
        assertEquals(listOf("You spent " to false, "₹450" to true, " today." to false), boldSegments("You spent **₹450** today."))
        assertEquals(listOf("plain" to false), boldSegments("plain"))
        assertEquals("Today 4:05 PM", aiWhenLabel(TODAY, 16, 5, TODAY))
        assertEquals("Yesterday 12:00 AM", aiWhenLabel("2026-10-13", 0, 0, TODAY))
        assertEquals("Mon 12 Oct 9:30 AM", aiWhenLabel("2026-10-12", 9, 30, TODAY))
        assertEquals("tasks, habits, goals, money, notes", aiScope(false))
        assertEquals("tasks, habits, goals, money, notes, wellness", aiScope(true))
    }

    @Test fun historyIsTheLastTen() {
        val ms = (1..12).map { AiMessage("m$it", if (it % 2 == 0) AiWho.Ai else AiWho.User, "t$it") }
        val h = askHistory(ms)
        assertEquals(10, h.size)
        assertEquals("user" to "t3", h.first())
        assertEquals("ai" to "t12", h.last())
    }

    @Test fun proposalLabelsAndActions() {
        val expense = AiProposal.Expense("Swiggy · ₹450", "", 450.0, "c1", "a1", "Swiggy")
        val task = AiProposal.Task("Call John", "", "Call John", "2026-10-15")
        val plan = AiProposal.Plan("Trip", "", "Trip", 100000.0, 6, 16667.0)
        val move = AiProposal.Reschedule(
            "Keep today: A", "", listOf(RescheduleMove("t1", "Clean the garage now", "2026-10-15", "Tomorrow"), RescheduleMove("t2", "Read", "2026-10-16", "Fri 16 Oct")),
        )
        assertEquals(listOf("Save", "Save", "Create goal + tasks", "Apply changes"), listOf(expense, task, plan, move).map(::proposalApplyLabel))
        assertEquals("Tasks rescheduled", proposalAppliedToast(move))

        val e = proposalActionDraft(expense, TODAY, listOf("tx1"))
        assertEquals("Created expense", e.action)
        assertEquals("Swiggy · ₹450", e.detail)
        assertEquals(listOf(RevertOp(RevertOp.DELETE_TRANSACTION, "tx1")), e.revert)
        assertEquals("Call John · Tomorrow", proposalActionDraft(task, TODAY, listOf("t9")).detail)
        assertEquals(
            listOf(RevertOp(RevertOp.DELETE_GOAL, "g1"), RevertOp(RevertOp.DELETE_TASK, "t1")),
            proposalActionDraft(plan, TODAY, listOf("g1", "t1")).revert,
        )
        val r = proposalActionDraft(move, TODAY, previousDue = mapOf("t1" to TODAY, "t2" to null))
        assertEquals("Rescheduled 2 tasks", r.action)
        assertEquals("Clean the → Tomorrow · Read → Fri 16 Oct", r.detail)
        assertEquals("Applied", r.state)
        assertEquals("warning", r.tone)
        assertEquals(
            listOf(RevertOp(RevertOp.UPDATE_TASK, "t1", "{\"due\":\"2026-10-14\"}"), RevertOp(RevertOp.UPDATE_TASK, "t2", "{\"due\":null}")),
            r.revert,
        )
    }

    @Test fun planBlueprintSpreadsMilestones() {
        val b = planBlueprint(AiProposal.Plan("Trip", "", "Trip", 100000.0, 6, 16667.0), TODAY)
        assertEquals("2027-04-12", b.due)
        assertEquals(GoalTerm.Short, b.term)
        assertEquals("Transfer ₹16,667 on the 2nd", b.nextAction)
        assertEquals(listOf("₹25,000", "₹50,000", "₹75,000", "₹1,00,000"), b.milestones.map { it.first })
        assertEquals(listOf("2026-11-28", "2027-01-12", "2027-02-26", "2027-04-12"), b.milestones.map { it.third })
        assertEquals("Transfer ₹16,667 to savings", b.taskTitle)
        assertEquals("2026-10-16", b.taskDue)
        assertEquals(GoalTerm.Medium, planBlueprint(AiProposal.Plan("x", "", "x", 1.0, 7, 1.0), TODAY).term)
    }

    @Test fun suggestionsAndLog() {
        val s = listOf(Suggestion("a", "bell", "A", "", ""), Suggestion("b", "bell", "B", "", "", state = SuggestionState.Dismissed))
        assertEquals(listOf("a"), suggestionsFor(s, open = true).map { it.id })
        assertEquals(listOf("b"), suggestionsFor(s, open = false).map { it.id })
        val d = suggestionActionDraft(s[0], emptyList())
        assertEquals("Applied suggestion", d.action)
        assertEquals(false, d.revertible)

        val log = listOf(
            AiAction("1", "2026-10-14T10:00:00Z", "Created task", "Call \"John\"", revertible = true),
            AiAction("2", "2026-09-01T10:00:00Z", "Reverted", "x", "By you"),
            AiAction("3", "2026-10-13T10:00:00Z", "Created expense", "y"),
        )
        assertEquals(listOf("All types", "Created", "Reverted"), aiLogTypes(log))
        assertEquals(listOf("1", "3"), filterAiLog(log, "Created", "2026-09-14") { it.at.take(10) }.map { it.id })
        assertEquals(3, filterAiLog(log, "All types", null) { it.at.take(10) }.size)
        assertEquals(
            "when,action,detail,state\n\"w\",\"Created task\",\"Call 'John'\",\"Approved by you\"",
            aiLogCsv(log.take(1)) { "w" },
        )
    }
}
