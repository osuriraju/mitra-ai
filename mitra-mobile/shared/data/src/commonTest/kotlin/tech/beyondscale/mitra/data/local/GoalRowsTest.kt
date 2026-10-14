package tech.beyondscale.mitra.data.local

import tech.beyondscale.mitra.data.db.Goal_progress
import tech.beyondscale.mitra.data.db.Goals
import tech.beyondscale.mitra.data.db.Milestones
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.ReviewEvery
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRowsTest {
    private fun goalRow(id: String, position: Long, formula: String? = null) = Goals(
        id, "Goal $id", "numeric", "Medium-term", "2027-03-31", "2026-10-01", 10.0, 100.0, "₹", formula,
        "Next", "Monthly", "2026-11-01", "active", "", "2026-10-01T04:30:00Z", position,
    )

    @Test fun assemblesMilestonesAndProgressInOrder() {
        val goals = listOf(goalRow("g1", 0), goalRow("g2", 1, formula = "sum"))
        val milestones = listOf(
            Milestones("m3", "g1", "Third", 30.0, null, null, null, 2),
            Milestones("m4", "g2", "Only", null, "2026-12-01", "2026-10-10", "n", 0),
            Milestones("m1", "g1", "First", null, null, null, null, 0),
            Milestones("m2", "g1", "Second", 20.0, null, "2026-10-12", null, 1),
        )
        val progress = listOf(
            Goal_progress("g1", 1, "2026-10-12", 20.0, null),
            Goal_progress("g2", 0, "2026-10-05", 5.0, "start"),
            Goal_progress("g1", 0, "2026-10-10", 10.0, "first"),
        )

        val out = assembleGoals(goals, milestones, progress)

        assertEquals(listOf("g1", "g2"), out.map { it.id })
        val g1 = out[0]
        assertEquals(listOf("m1", "m2", "m3"), g1.milestones.map { it.id })
        assertEquals(null, g1.milestones[0].target)
        assertEquals(null, g1.formula)
        assertEquals(listOf(ProgressEntry("2026-10-10", 10.0, "first"), ProgressEntry("2026-10-12", 20.0, null)), g1.progressLog)
        val g2 = out[1]
        assertEquals("sum", g2.formula)
        assertEquals(listOf(Milestone("m4", "Only", null, "2026-12-01", "2026-10-10", "n")), g2.milestones)
        assertEquals(listOf(ProgressEntry("2026-10-05", 5.0, "start")), g2.progressLog)
        assertEquals(GoalType.Numeric, g2.type)
        assertEquals(GoalTerm.Medium, g2.term)
        assertEquals(ReviewEvery.Monthly, g2.reviewEvery)
        assertEquals(GoalState.Active, g2.state)
    }

    @Test fun writeToReplacesGoalMilestonesAndProgress() {
        val db = MitraDb(testDriver())
        val goal = Goal(
            id = "g1", name = "Save", type = GoalType.Milestone, term = GoalTerm.Long, due = "2027-12-31",
            startedOn = "2026-10-01", current = 1.0, target = 2.0, unit = "", formula = null, nextAction = "",
            reviewEvery = ReviewEvery.Quarterly, reviewOn = "2027-01-01", state = GoalState.Paused, notes = "n",
            createdAt = "2026-10-01T04:30:00Z",
            milestones = listOf(Milestone("m1", "A", reachedOn = "2026-10-02"), Milestone("m2", "B", target = 5.5)),
            progressLog = listOf(ProgressEntry("2026-10-02", 1.0, "A"), ProgressEntry("2026-10-03", 1.0)),
        )
        goal.writeTo(db, position = 4)
        assertEquals(listOf(goal), db.readGoals())
        assertEquals(4L, db.goalsQueries.selectById("g1").executeAsOne().position)

        val shorter = goal.copy(milestones = listOf(Milestone("m2", "B2")), progressLog = emptyList())
        shorter.writeTo(db, position = 4)
        assertEquals(listOf(shorter), db.readGoals())
    }

    @Test fun unknownWireEnumFallsBack() {
        val row = goalRow("g1", 0).copy(type = "x", term = "y", reviewEvery = "z", status = "w")
        val goal = assembleGoals(listOf(row), emptyList(), emptyList()).single()
        assertEquals(GoalType.Numeric, goal.type)
        assertEquals(GoalTerm.Short, goal.term)
        assertEquals(ReviewEvery.Weekly, goal.reviewEvery)
        assertEquals(GoalState.Active, goal.state)
    }

    private fun MitraDb.readGoals(): List<Goal> = assembleGoals(
        goalsQueries.selectAll().executeAsList(),
        milestonesQueries.selectAll().executeAsList(),
        goalProgressQueries.selectAll().executeAsList(),
    )
}
