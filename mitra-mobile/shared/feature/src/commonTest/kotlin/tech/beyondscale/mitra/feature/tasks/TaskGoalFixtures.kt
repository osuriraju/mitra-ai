package tech.beyondscale.mitra.feature.tasks

import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.ReviewEvery

/** A numeric goal with only the fields the task screens read set meaningfully. */
internal fun linkGoal(id: String, name: String, state: GoalState = GoalState.Active, due: String = "2027-04-12") = Goal(
    id = id, name = name, type = GoalType.Numeric, term = GoalTerm.Medium, due = due, startedOn = "2026-07-01",
    current = 0.0, target = 100.0, unit = "", formula = null, nextAction = "", reviewEvery = ReviewEvery.Monthly,
    reviewOn = "2026-11-01", state = state, notes = "", createdAt = "2026-07-01T00:00:00Z", milestones = emptyList(),
    progressLog = emptyList(),
)
