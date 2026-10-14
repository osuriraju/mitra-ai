package tech.beyondscale.mitra.domain.goals

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.diffDays
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalDraft
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.MilestoneChange
import tech.beyondscale.mitra.domain.model.ProgressEntry

/*
 * The server's derived goal data (mitra-backend/src/goals/goals.service.ts), applied locally so a write shows the
 * same numbers the echo will bring. Used by GoalsRepositoryImpl and FakeGoalsRepository; every result is `.derived()`.
 */

/**
 * A new goal from the form (web `GoalForm.save`, create path): milestone targets (numeric only) and dates spread
 * evenly to the due date, `startedOn = today`, `reviewOn = today + reviewEvery`.
 */
fun newGoal(id: String, draft: GoalDraft, today: String, createdAt: String, newId: () -> String): Goal {
    val target = if (draft.target == 0.0 || draft.target.isNaN()) 100.0 else draft.target // `+target || 100`
    val titles = draft.milestoneTitles.map { it.trim() }.filter { it.isNotEmpty() }
    val n = titles.size
    val span = diffDays(draft.due, today)
    val milestones = titles.mapIndexed { i, title ->
        Milestone(
            id = newId(),
            title = title,
            target = if (draft.type == GoalType.Numeric) jsRound(target * (i + 1) / n).toDouble() else null,
            targetDate = addDays(today, jsRound(span.toDouble() * (i + 1) / n).toInt()),
        )
    }
    val milestoneType = draft.type == GoalType.Milestone
    return Goal(
        id = id, name = draft.name.trim(), type = draft.type, term = draft.term, due = draft.due, startedOn = today,
        current = if (milestoneType) 0.0 else draft.current, target = if (milestoneType) n.toDouble() else target,
        unit = draft.unit, formula = null, nextAction = draft.nextAction.trim(), reviewEvery = draft.reviewEvery,
        reviewOn = addDays(today, draft.reviewEvery.days), state = GoalState.Active, notes = "", createdAt = createdAt,
        milestones = milestones, progressLog = emptyList(),
    ).derived()
}

/** `goalOut`: milestone-type goals report current = reached milestones and target = milestone count. */
fun Goal.derived(): Goal =
    if (type != GoalType.Milestone) this
    else copy(current = milestones.count { it.reachedOn != null }.toDouble(), target = milestones.size.toDouble())

/** A PATCH applied locally (no derived side effects on the server beyond `goalOut`). */
fun Goal.applyChanges(changes: List<GoalChange>): Goal = changes.fold(this) { g, c ->
    when (c) {
        is GoalChange.Name -> g.copy(name = c.v)
        is GoalChange.Type -> g.copy(type = c.v)
        is GoalChange.Term -> g.copy(term = c.v)
        is GoalChange.Due -> g.copy(due = c.v)
        is GoalChange.Current -> g.copy(current = c.v)
        is GoalChange.Target -> g.copy(target = c.v)
        is GoalChange.Unit -> g.copy(unit = c.v)
        is GoalChange.Formula -> g.copy(formula = c.v)
        is GoalChange.NextAction -> g.copy(nextAction = c.v)
        is GoalChange.ReviewEvery -> g.copy(reviewEvery = c.v)
        is GoalChange.ReviewOn -> g.copy(reviewOn = c.v)
        is GoalChange.State -> g.copy(state = c.v)
        is GoalChange.Notes -> g.copy(notes = c.v)
    }
}.derived()

/**
 * `POST goals/:id/progress`: numeric goals add [value], other types set it; open milestones with a target at or
 * below the new value are reached on [date]; `{date, current, note}` is appended; the goal completes only when it
 * is not milestone/habit-type, reaches its target and is still active.
 */
fun Goal.applyProgress(value: Double, note: String?, date: String): Goal {
    val next = if (type == GoalType.Numeric) current + value else value
    val ms = milestones.map { m -> if (m.target != null && m.reachedOn == null && next >= m.target) m.copy(reachedOn = date) else m }
    val done = type != GoalType.Milestone && type != GoalType.Habit && next >= target
    return copy(
        current = next,
        milestones = ms,
        progressLog = progressLog + ProgressEntry(date, next, note),
        state = if (done && state == GoalState.Active) GoalState.Completed else state,
    ).derived()
}

/** `POST goals/:id/milestones`: appended last. */
fun Goal.withMilestone(m: Milestone): Goal = copy(milestones = milestones + m).derived()

/**
 * `PATCH goals/:id/milestones/:mid`. Reaching a milestone (no date → a date) on a milestone-type goal appends
 * `{reachedOn, reached count, note = the title before this patch}`; un-reaching removes nothing. Unknown id → unchanged.
 */
fun Goal.applyMilestone(id: String, changes: List<MilestoneChange>): Goal {
    val old = milestones.firstOrNull { it.id == id } ?: return this
    val updated = changes.fold(old) { m, c ->
        when (c) {
            is MilestoneChange.Title -> m.copy(title = c.v)
            is MilestoneChange.Target -> m.copy(target = c.v)
            is MilestoneChange.TargetDate -> m.copy(targetDate = c.v)
            is MilestoneChange.ReachedOn -> m.copy(reachedOn = c.v)
            is MilestoneChange.Notes -> m.copy(notes = c.v)
        }
    }
    val ms = milestones.map { if (it.id == id) updated else it }
    val reachedNow = updated.reachedOn
    val log = if (!reachedNow.isNullOrEmpty() && old.reachedOn == null && type == GoalType.Milestone) {
        progressLog + ProgressEntry(reachedNow, ms.count { it.reachedOn != null }.toDouble(), old.title)
    } else progressLog
    return copy(milestones = ms, progressLog = log).derived()
}

/** `DELETE goals/:id/milestones/:mid`. */
fun Goal.withoutMilestone(id: String): Goal = copy(milestones = milestones.filter { it.id != id }).derived()
