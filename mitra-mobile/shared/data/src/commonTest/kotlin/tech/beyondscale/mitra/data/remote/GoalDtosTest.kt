package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.json.Json
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.ReviewEvery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalDtosTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun decodesNumbersAndOptionals() {
        val goal = json.decodeFromString(
            GoalDto.serializer(),
            """{"id":"g1","name":"Emergency fund","type":"numeric","term":"Medium-term","due":"2027-03-31","startedOn":"2026-04-01",
               "current":58000,"target":100000.5,"unit":"₹","nextAction":"Move ₹5k","reviewEvery":"Monthly","reviewOn":"2026-11-01",
               "status":"archived","notes":"","createdAt":"2026-04-01T04:00:00.000Z",
               "milestones":[{"id":"m1","title":"Half way","targetDate":"2026-10-01"},{"id":"m2","title":"Done","target":100000,"reachedOn":"2026-10-02","notes":"n"}],
               "progressLog":[{"date":"2026-09-01","value":50000},{"date":"2026-10-01","value":58000,"note":"Bonus"}]}""",
        ).toDomain()
        assertEquals(
            Goal(
                id = "g1", name = "Emergency fund", type = GoalType.Numeric, term = GoalTerm.Medium, due = "2027-03-31",
                startedOn = "2026-04-01", current = 58000.0, target = 100000.5, unit = "₹", formula = null, nextAction = "Move ₹5k",
                reviewEvery = ReviewEvery.Monthly, reviewOn = "2026-11-01", state = GoalState.Active, notes = "",
                createdAt = "2026-04-01T04:00:00.000Z",
                milestones = listOf(
                    Milestone("m1", "Half way", target = null, targetDate = "2026-10-01", reachedOn = null, notes = null),
                    Milestone("m2", "Done", target = 100000.0, targetDate = null, reachedOn = "2026-10-02", notes = "n"),
                ),
                progressLog = listOf(ProgressEntry("2026-09-01", 50000.0, null), ProgressEntry("2026-10-01", 58000.0, "Bonus")),
            ),
            goal,
        )
        assertNull(goal.formula)
        // Unknown wire enums fall back to the first constant instead of throwing.
        val odd = json.decodeFromString(
            GoalDto.serializer(),
            """{"id":"g2","name":"X","type":"weird","term":"Forever","due":"2027-01-01","startedOn":"2026-01-01","current":0,"target":1,
               "unit":"","nextAction":"","reviewEvery":"Daily","reviewOn":"2026-02-01","status":"paused","notes":"","createdAt":"c","milestones":[],"progressLog":[]}""",
        ).toDomain()
        assertEquals(GoalType.Numeric, odd.type)
        assertEquals(GoalTerm.Short, odd.term)
        assertEquals(ReviewEvery.Weekly, odd.reviewEvery)
        assertEquals(GoalState.Paused, odd.state)
    }

    @Test fun createBodyMatchesTheWebAddGoal() {
        val g = Goal(
            id = "g1", name = "Run", type = GoalType.Milestone, term = GoalTerm.Short, due = "2027-01-01", startedOn = "2026-10-14",
            current = 0.0, target = 1.0, unit = "", formula = null, nextAction = "", reviewEvery = ReviewEvery.Weekly,
            reviewOn = "2026-10-21", state = GoalState.Active, notes = "", createdAt = "2026-10-14T04:30:00Z",
            milestones = listOf(Milestone("m1", "5k", target = null, targetDate = "2027-01-01")), progressLog = emptyList(),
        )
        assertEquals(
            """{"id":"g1","name":"Run","type":"milestone","term":"Short-term","due":"2027-01-01","startedOn":"2026-10-14","current":0.0,"target":1.0,""" +
                """"unit":"","formula":null,"nextAction":"","reviewEvery":"Weekly","reviewOn":"2026-10-21","status":"active","notes":"",""" +
                """"milestones":[{"id":"m1","title":"5k","target":null,"targetDate":"2027-01-01","reachedOn":null,"notes":null}]}""",
            g.createBody().toString(),
        )
        assertEquals(
            """{"id":"m1","title":"5k","target":null,"targetDate":"2027-01-01","reachedOn":null,"notes":null}""",
            milestoneBody(g.milestones.single()).toString(),
        )
    }
}
