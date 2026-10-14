package tech.beyondscale.mitra.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tech.beyondscale.mitra.domain.model.ActivityItem
import tech.beyondscale.mitra.domain.model.AiAction
import tech.beyondscale.mitra.domain.model.AiMessage
import tech.beyondscale.mitra.domain.model.AiProposal
import tech.beyondscale.mitra.domain.model.AiWho
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.RescheduleMove
import tech.beyondscale.mitra.domain.model.RevertOp
import tech.beyondscale.mitra.domain.model.Suggestion
import tech.beyondscale.mitra.domain.model.SuggestionAction
import tech.beyondscale.mitra.domain.model.SuggestionState
import tech.beyondscale.mitra.domain.model.TriageMove
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.model.WellnessTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Phase5RowsTest {
    @Test fun wellnessRoundTrips() {
        val e = WellnessEntry("2026-10-14", 3, "23:30", "06:30", 8000, 6, 72.5, "ok", "2026-10-14")
        assertEquals(e, e.toRow().toDomain())
        val empty = WellnessEntry("2026-10-13")
        assertEquals(empty, empty.toRow().toDomain())
        val s = WellnessSettings(172, 480, 10000, 10, WellnessTrack(steps = false, bmi = false), true, false, "06:45", "22:00")
        assertEquals(WELLNESS_SETTINGS_ID, s.toRow().id)
        assertEquals(s, s.toRow().toDomain())
    }

    @Test fun noteRoundTrips() {
        val n = Note("n1", "Idea", "- [ ] a", "Ideas", listOf("money", "ideas"), "2026-10-14", true, 4, "2026-10-01", "2026-10-14")
        val row = n.toRow(7)
        assertEquals(7L, row.sortKey)
        assertEquals("[\"money\",\"ideas\"]", row.tags)
        assertEquals(n, row.toDomain())
        assertEquals(emptyList(), row.copy(tags = "not json").toDomain().tags)
    }

    @Test fun aiRowsRoundTrip() {
        val proposals = listOf(
            AiProposal.Expense("Swiggy · ₹450", "Food · Today · HDFC", 450.0, "c1", "a1", "Swiggy", "dinner"),
            AiProposal.Task("Call John", "Tomorrow · 6:00 PM", "Call John", "2026-10-15", "18:00", null),
            AiProposal.Plan("Save 1L", "4 milestones", "Save 1L", 100000.0, 6, 16667.0),
            AiProposal.Reschedule("Keep today: A", "Move 1 task", listOf(RescheduleMove("t1", "B", "2026-10-15", "Tomorrow"))),
        )
        proposals.forEachIndexed { i, p ->
            val m = AiMessage("m$i", AiWho.Ai, "Here", listOf(1.0, 2.5), p, applied = i == 0, createdAt = 10L + i)
            assertEquals(m, m.toRow().toDomain())
        }
        val plain = AiMessage("u", AiWho.User, "hi", createdAt = 1)
        assertEquals(plain, plain.toRow().toDomain())

        val a = AiAction(
            "x1", "2026-10-14T04:30:00.000Z", "Rescheduled 1 tasks", "B → Tomorrow", "Applied", "warning", true, false,
            listOf(RevertOp(RevertOp.UPDATE_TASK, "t1", "{\"due\":null}"), RevertOp(RevertOp.DELETE_TASK, "t2")),
        )
        assertEquals(a, a.toRow().toDomain())
        assertNull(a.copy(revert = emptyList()).toRow().revertJson)

        val actions = listOf(
            SuggestionAction.SetHabitReminder("h1", "07:00"),
            SuggestionAction.AddRecurring("Rent", 15000.0, 2, "c1"),
            SuggestionAction.AddSubtasks("t1", listOf("a", "b")),
            SuggestionAction.TriageInbox(listOf(TriageMove("t1", "p1", null))),
            SuggestionAction.PauseGoal("g1"),
            SuggestionAction.SetBudget("c1", 5000.0),
            SuggestionAction.AddTask("Pay", "2026-10-20"),
        )
        actions.forEachIndexed { i, act ->
            val s = Suggestion("sg-$i", "bell", "T", "why", "Habits", "accent", "will change", act, SuggestionState.Dismissed)
            assertEquals(s, s.toRow(i.toLong()).toDomain())
        }

        val item = ActivityItem("ac1", "Check-in", "heart", "info", "wellness", null, "2026-10-14T04:30:00.000Z")
        assertEquals(item, item.toRow().toDomain())
    }

    @Test fun decodesServerShapesLeniently() {
        val o = Json.parseToJsonElement("""{"kind":"plan","title":"G","sub":"s","goalName":"G","target":100000,"months":6,"monthly":16667}""").jsonObject
        assertEquals(AiProposal.Plan("G", "s", "G", 100000.0, 6, 16667.0), aiProposalFromJson(o))
        assertNull(aiProposalFromJson(Json.parseToJsonElement("""{"kind":"expense","title":"x"}""").jsonObject))
        assertNull(suggestionActionFromJson(Json.parseToJsonElement("""{"kind":"teleport"}""").jsonObject))
        val ops = revertOpsFromJson(Json.parseToJsonElement("""[{"op":"deleteTask","id":"t1"},{"id":"x"},{"op":"updateGoal","id":"g1","patch":{"status":"active"}}]"""))
        assertEquals(listOf(RevertOp("deleteTask", "t1"), RevertOp("updateGoal", "g1", "{\"status\":\"active\"}")), ops)
    }
}
