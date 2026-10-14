package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalDraft
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.MilestoneChange
import tech.beyondscale.mitra.domain.model.MilestoneDraft

/**
 * Goals, offline-first. Every write applies the server's derived maths locally (`domain/goals/GoalMath.kt`), so
 * the numbers on screen equal the server's echo.
 */
interface GoalsRepository {
    /** All goals in every state, in server order (createdAt ascending). */
    val goals: Flow<List<Goal>>

    /** Re-reads `goals`; goals with queued changes are kept. See [TasksRepository.refresh] for [REFRESH_SUPERSEDED]. */
    suspend fun refresh(): Outcome<Unit>

    suspend fun create(draft: GoalDraft): Goal

    suspend fun update(id: String, changes: List<GoalChange>)

    suspend fun delete(id: String)

    /** Numeric goals add [value]; other types set it. Always dated with the device's local today. */
    suspend fun logProgress(id: String, value: Double, note: String?)

    /** Null when the goal is unknown. */
    suspend fun addMilestone(goalId: String, draft: MilestoneDraft): Milestone?

    suspend fun updateMilestone(goalId: String, id: String, changes: List<MilestoneChange>)

    suspend fun deleteMilestone(goalId: String, id: String)

    companion object {
        const val REFRESH_SUPERSEDED = TasksRepository.REFRESH_SUPERSEDED
    }
}
