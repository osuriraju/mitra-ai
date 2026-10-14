package tech.beyondscale.mitra.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitChange
import tech.beyondscale.mitra.domain.model.HabitDraft
import tech.beyondscale.mitra.domain.model.applyChanges
import tech.beyondscale.mitra.domain.model.toHabit
import tech.beyondscale.mitra.domain.model.withLog
import tech.beyondscale.mitra.domain.repository.HabitsRepository

/** In-memory [HabitsRepository]: writes apply locally like the real repository and are recorded in [calls]. */
class FakeHabitsRepository(initial: List<Habit> = emptyList()) : HabitsRepository {
    val habitFlow = MutableStateFlow(initial)
    override val habits: Flow<List<Habit>> = habitFlow
    val calls = mutableListOf<String>()
    var refreshResult: Outcome<Unit> = Outcome.Ok(Unit)
    private var seq = 0

    override suspend fun refresh(): Outcome<Unit> { calls += "refresh"; return refreshResult }

    override suspend fun create(draft: HabitDraft): Habit {
        val h = draft.toHabit("habit${++seq}", createdAt = "")
        calls += "create:${h.name}"
        habitFlow.update { it + h }
        return h
    }

    override suspend fun update(id: String, changes: List<HabitChange>) {
        calls += "update:$id"
        habitFlow.update { list -> list.map { if (it.id == id) it.applyChanges(changes) else it } }
    }

    override suspend fun delete(id: String) {
        calls += "delete:$id"
        habitFlow.update { list -> list.filter { it.id != id } }
    }

    override suspend fun setLog(id: String, date: String, value: Int?) {
        calls += "log:$id:$date:$value"
        habitFlow.update { list -> list.map { if (it.id == id) it.withLog(date, value) else it } }
    }
}
