package tech.beyondscale.mitra.data.local

import tech.beyondscale.mitra.data.db.Goal_progress
import tech.beyondscale.mitra.data.db.Goals
import tech.beyondscale.mitra.data.db.Milestones
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.ReviewEvery

/**
 * Joins goal rows with their milestones and progress rows (grouped in one pass), each list ordered by `position`.
 * Goals keep the order of [goals]. Unknown wire enums fall back through the domain `fromWire` (never throws).
 */
fun assembleGoals(goals: List<Goals>, milestones: List<Milestones>, progress: List<Goal_progress>): List<Goal> {
    val milestonesByGoal = milestones.groupBy { it.goalId }
    val progressByGoal = progress.groupBy { it.goalId }
    return goals.map { g ->
        Goal(
            id = g.id, name = g.name, type = GoalType.fromWire(g.type), term = GoalTerm.fromWire(g.term), due = g.due,
            startedOn = g.startedOn, current = g.current, target = g.target, unit = g.unit, formula = g.formula,
            nextAction = g.nextAction, reviewEvery = ReviewEvery.fromWire(g.reviewEvery), reviewOn = g.reviewOn,
            state = GoalState.fromWire(g.status), notes = g.notes, createdAt = g.createdAt,
            milestones = milestonesByGoal[g.id].orEmpty().sortedBy { it.position }
                .map { Milestone(it.id, it.title, it.target, it.targetDate, it.reachedOn, it.notes) },
            progressLog = progressByGoal[g.id].orEmpty().sortedBy { it.position }
                .map { ProgressEntry(it.date, it.value_, it.note) },
        )
    }
}

/** Replaces the goal row, its milestones and its progress rows. Call inside a transaction. */
fun Goal.writeTo(db: MitraDb, position: Long) {
    db.goalsQueries.upsert(
        Goals(
            id, name, type.wire, term.wire, due, startedOn, current, target, unit, formula, nextAction,
            reviewEvery.wire, reviewOn, state.wire, notes, createdAt, position,
        ),
    )
    db.milestonesQueries.deleteForGoal(id)
    milestones.forEachIndexed { i, m ->
        db.milestonesQueries.upsert(Milestones(m.id, id, m.title, m.target, m.targetDate, m.reachedOn, m.notes, i.toLong()))
    }
    db.goalProgressQueries.deleteForGoal(id)
    progressLog.forEachIndexed { i, p ->
        db.goalProgressQueries.insert(Goal_progress(id, i.toLong(), p.date, p.value, p.note))
    }
}
