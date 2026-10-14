package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitChange
import tech.beyondscale.mitra.domain.model.HabitDraft

/** Habits, offline-first: writes apply locally at once and sync through the outbox. */
interface HabitsRepository {
    /** All habits including archived, in server order (createdAt ascending). */
    val habits: Flow<List<Habit>>

    /** Re-reads `habits`; habits with queued changes are kept. See [TasksRepository.refresh] for [REFRESH_SUPERSEDED]. */
    suspend fun refresh(): Outcome<Unit>

    suspend fun create(draft: HabitDraft): Habit

    suspend fun update(id: String, changes: List<HabitChange>)

    suspend fun delete(id: String)

    /** Logs [value] for the local [date]; `null` or `0` removes that day's log. Unknown id = no-op. */
    suspend fun setLog(id: String, date: String, value: Int?)

    companion object {
        const val REFRESH_SUPERSEDED = TasksRepository.REFRESH_SUPERSEDED
    }
}
