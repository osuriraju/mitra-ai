package tech.beyondscale.mitra.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.goals.applyChanges
import tech.beyondscale.mitra.domain.goals.applyMilestone
import tech.beyondscale.mitra.domain.goals.applyProgress
import tech.beyondscale.mitra.domain.goals.newGoal
import tech.beyondscale.mitra.domain.goals.withMilestone
import tech.beyondscale.mitra.domain.goals.withoutMilestone
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalDraft
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.MilestoneChange
import tech.beyondscale.mitra.domain.model.MilestoneDraft
import tech.beyondscale.mitra.domain.repository.GoalsRepository

/**
 * In-memory [GoalsRepository]: writes apply the server's maths through `GoalMath` (so VM tests see the numbers the
 * echo would bring) and are recorded in [calls].
 */
class FakeGoalsRepository(initial: List<Goal> = emptyList(), private val clock: AppClock) : GoalsRepository {
    val goalFlow = MutableStateFlow(initial)
    override val goals: Flow<List<Goal>> = goalFlow
    val calls = mutableListOf<String>()
    var refreshResult: Outcome<Unit> = Outcome.Ok(Unit)
    private var goalSeq = 0
    private var msSeq = 0

    override suspend fun refresh(): Outcome<Unit> { calls += "refresh"; return refreshResult }

    override suspend fun create(draft: GoalDraft): Goal {
        val g = newGoal("goal${++goalSeq}", draft, clock.today(), clock.now().toString()) { "ms${++msSeq}" }
        calls += "create:${g.name}"
        goalFlow.update { it + g }
        return g
    }

    override suspend fun update(id: String, changes: List<GoalChange>) {
        calls += "update:$id"
        edit(id) { it.applyChanges(changes) }
    }

    override suspend fun delete(id: String) {
        calls += "delete:$id"
        goalFlow.update { list -> list.filter { it.id != id } }
    }

    override suspend fun logProgress(id: String, value: Double, note: String?) {
        calls += "progress:$id:$value"
        edit(id) { it.applyProgress(value, note, clock.today()) }
    }

    override suspend fun addMilestone(goalId: String, draft: MilestoneDraft): Milestone? {
        calls += "addMilestone:$goalId"
        if (goalFlow.value.none { it.id == goalId }) return null
        val m = Milestone("ms${++msSeq}", draft.title.trim(), draft.target, draft.targetDate, reachedOn = null, notes = draft.notes)
        edit(goalId) { it.withMilestone(m) }
        return m
    }

    override suspend fun updateMilestone(goalId: String, id: String, changes: List<MilestoneChange>) {
        calls += "updateMilestone:$id"
        edit(goalId) { it.applyMilestone(id, changes) }
    }

    override suspend fun deleteMilestone(goalId: String, id: String) {
        calls += "deleteMilestone:$id"
        edit(goalId) { it.withoutMilestone(id) }
    }

    private fun edit(id: String, f: (Goal) -> Goal) = goalFlow.update { list -> list.map { if (it.id == id) f(it) else it } }
}
