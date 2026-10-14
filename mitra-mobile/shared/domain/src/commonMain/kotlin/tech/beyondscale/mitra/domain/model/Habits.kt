package tech.beyondscale.mitra.domain.model

/* Habit models — wire values as mitra-backend/src/habits/schemas.ts. Days are Mon..Sun. */

enum class HabitType(val wire: String, val label: String) {
    Binary("binary", "Yes / No"),
    Count("count", "Count"),
    Duration("duration", "Duration"),
    Quantity("quantity", "Quantity"),
    ;

    companion object { fun fromWire(s: String?): HabitType = entries.firstOrNull { it.wire == s } ?: Binary }
}

enum class TimeOfDay(val wire: String, val label: String) {
    Morning("morning", "Morning"),
    Anytime("anytime", "Anytime"),
    Evening("evening", "Evening"),
    ;

    companion object { fun fromWire(s: String?): TimeOfDay = entries.firstOrNull { it.wire == s } ?: Anytime }
}

val ALL_DAYS: List<Boolean> = List(7) { true }
val WEEKDAYS: List<Boolean> = listOf(true, true, true, true, true, false, false)
val THREE_A_WEEK: List<Boolean> = listOf(true, false, true, false, true, false, false)

data class Habit(
    val id: String,
    val name: String,
    val type: HabitType,
    val target: Int,
    val unit: String,
    val icon: String,
    val timeOfDay: TimeOfDay,
    /** Mon..Sun. */
    val days: List<Boolean>,
    /** "HH:MM" or null. */
    val reminder: String?,
    val goalId: String?,
    val gentle: Boolean,
    val archived: Boolean,
    val createdAt: String,
    /** Local ISO date → logged value. */
    val logs: Map<String, Int>,
)

/** What the habit form produces; the repository assigns the id. */
data class HabitDraft(
    val name: String,
    val type: HabitType = HabitType.Binary,
    val target: Int = 1,
    val unit: String = "",
    val icon: String = "repeat",
    val timeOfDay: TimeOfDay = TimeOfDay.Anytime,
    val days: List<Boolean> = ALL_DAYS,
    val reminder: String? = null,
    val goalId: String? = null,
    val gentle: Boolean = true,
)

sealed interface HabitChange {
    data class Name(val v: String) : HabitChange
    data class Type(val v: HabitType) : HabitChange
    data class Target(val v: Int) : HabitChange
    data class Unit(val v: String) : HabitChange
    data class Icon(val v: String) : HabitChange
    data class TimeOfDay(val v: tech.beyondscale.mitra.domain.model.TimeOfDay) : HabitChange
    data class Days(val v: List<Boolean>) : HabitChange
    data class Reminder(val v: String?) : HabitChange
    data class GoalId(val v: String?) : HabitChange
    data class Gentle(val v: Boolean) : HabitChange
    data class Archived(val v: Boolean) : HabitChange
}

/** A new habit from the form; binary habits always have target 1 and no unit (web `HabitForm.save`). */
fun HabitDraft.toHabit(id: String, createdAt: String): Habit {
    val binary = type == HabitType.Binary
    return Habit(
        id = id, name = name.trim(), type = type, target = if (binary) 1 else target, unit = if (binary) "" else unit,
        icon = icon, timeOfDay = timeOfDay, days = days, reminder = reminder, goalId = goalId, gentle = gentle,
        archived = false, createdAt = createdAt, logs = emptyMap(),
    )
}

/** A PATCH applied locally. */
fun Habit.applyChanges(changes: List<HabitChange>): Habit = changes.fold(this) { h, c ->
    when (c) {
        is HabitChange.Name -> h.copy(name = c.v)
        is HabitChange.Type -> h.copy(type = c.v)
        is HabitChange.Target -> h.copy(target = c.v)
        is HabitChange.Unit -> h.copy(unit = c.v)
        is HabitChange.Icon -> h.copy(icon = c.v)
        is HabitChange.TimeOfDay -> h.copy(timeOfDay = c.v)
        is HabitChange.Days -> h.copy(days = c.v)
        is HabitChange.Reminder -> h.copy(reminder = c.v)
        is HabitChange.GoalId -> h.copy(goalId = c.v)
        is HabitChange.Gentle -> h.copy(gentle = c.v)
        is HabitChange.Archived -> h.copy(archived = c.v)
    }
}

/** `PUT habits/:id/logs`: `null` or `0` removes the day's log. */
fun Habit.withLog(date: String, value: Int?): Habit =
    copy(logs = if (value == null || value == 0) logs - date else logs + (date to value))
