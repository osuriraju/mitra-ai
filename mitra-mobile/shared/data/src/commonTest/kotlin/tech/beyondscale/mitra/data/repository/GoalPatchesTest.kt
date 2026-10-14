package tech.beyondscale.mitra.data.repository

import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.MilestoneChange
import tech.beyondscale.mitra.domain.model.ReviewEvery
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalPatchesTest {
    @Test fun onlyChangedKeys() {
        assertEquals("""{"formula":null}""", goalPatch(listOf(GoalChange.Formula(null))).toString())
        assertEquals("""{"status":"paused"}""", goalPatch(listOf(GoalChange.State(GoalState.Paused))).toString())
        assertEquals(
            """{"reviewOn":"2026-11-13","target":120000.0}""",
            goalPatch(listOf(GoalChange.ReviewOn("2026-11-13"), GoalChange.Target(120000.0))).toString(),
        )
        assertEquals("""{"reviewEvery":"Quarterly"}""", goalPatch(listOf(GoalChange.ReviewEvery(ReviewEvery.Quarterly))).toString())
        assertEquals("{}", goalPatch(emptyList()).toString())
    }

    @Test fun milestonePatchSendsNullForClears() {
        assertEquals(
            """{"reachedOn":null,"target":null,"title":"Half"}""",
            milestonePatch(listOf(MilestoneChange.ReachedOn(null), MilestoneChange.Target(null), MilestoneChange.Title("Half"))).toString(),
        )
        assertEquals("""{"targetDate":"2026-12-01","notes":null}""", milestonePatch(listOf(MilestoneChange.TargetDate("2026-12-01"), MilestoneChange.Notes(null))).toString())
    }

    @Test fun trimsLikeTheServer() {
        // zod `.trim()` on name, nextAction and milestone title: the local copy equals the echo.
        assertEquals(
            listOf(GoalChange.Name("Run"), GoalChange.NextAction("Book"), GoalChange.Notes(" keep ")),
            listOf(GoalChange.Name("  Run "), GoalChange.NextAction("Book  "), GoalChange.Notes(" keep ")).normalized(),
        )
        assertEquals(listOf(MilestoneChange.Title("5k")), listOf(MilestoneChange.Title(" 5k ")).normalizedMilestone())
        assertEquals("""{"name":"Run"}""", goalPatch(listOf(GoalChange.Name(" Run "))).toString())
    }
}
