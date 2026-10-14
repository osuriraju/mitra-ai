package tech.beyondscale.mitra.domain.habits

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.domain.model.ALL_DAYS
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.TimeOfDay
import tech.beyondscale.mitra.domain.model.WEEKDAYS

/** Wednesday — every test's "today". */
internal const val TODAY = "2026-10-14"

internal fun habit(
    id: String = "h",
    name: String = "Read",
    type: HabitType = HabitType.Binary,
    target: Int = 1,
    unit: String = "",
    days: List<Boolean> = ALL_DAYS,
    logs: Map<String, Int> = emptyMap(),
    archived: Boolean = false,
    goalId: String? = null,
    icon: String = "book",
    timeOfDay: TimeOfDay = TimeOfDay.Anytime,
) = Habit(id, name, type, target, unit, icon, timeOfDay, days, reminder = null, goalId = goalId, gentle = true, archived = archived, createdAt = "2026-09-01T00:00:00.000Z", logs = logs)

/** `value` logged on each date. */
internal fun logs(vararg dates: String, value: Int = 1): Map<String, Int> = dates.associateWith { value }

/** [n] consecutive days ending yesterday (today stays open). */
internal fun lastDays(n: Int, value: Int = 1, today: String = TODAY): Map<String, Int> = (1..n).associate { addDays(today, -it) to value }

/** Logs [done] of the habit's due days inside the [window] days ending today, oldest first. */
internal fun Habit.withFirstDueLogged(done: Int, window: Int = 30, today: String = TODAY): Habit {
    val due = (window - 1 downTo 0).map { addDays(today, -it) }.filter { habitDue(this, it) }
    return copy(logs = due.take(done).associateWith { target })
}

/* The web seed's habit definitions (mitra-frontend/src/store/seed.ts) — logs are set per test. */
internal val morningWalk = habit("h-walk", "Morning walk", HabitType.Duration, 30, "min", icon = "activity", timeOfDay = TimeOfDay.Morning, goalId = "g-10k")
internal val drinkWater = habit("h-water", "Drink water", HabitType.Count, 8, "glasses", icon = "droplet")
internal val read = habit("h-read", "Read", HabitType.Duration, 20, "min", icon = "book", goalId = "g-books")
internal val noSugar = habit("h-sugar", "No sugar", HabitType.Binary, 1, "", days = WEEKDAYS, icon = "x")
internal val sleepBy11 = habit("h-sleep", "Sleep by 11 pm", HabitType.Binary, 1, "", icon = "moon", timeOfDay = TimeOfDay.Evening)
