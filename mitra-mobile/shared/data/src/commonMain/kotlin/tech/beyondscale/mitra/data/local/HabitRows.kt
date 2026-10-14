package tech.beyondscale.mitra.data.local

import tech.beyondscale.mitra.data.db.Habits
import tech.beyondscale.mitra.domain.model.ALL_DAYS
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.TimeOfDay

/** Mon..Sun as a 7-char "1111100" string. */
fun daysToText(days: List<Boolean>): String = days.joinToString("") { if (it) "1" else "0" }

/** The inverse of [daysToText]; anything malformed reads as every day. */
fun textToDays(text: String): List<Boolean> =
    if (text.length == 7 && text.all { it == '0' || it == '1' }) text.map { it == '1' } else ALL_DAYS

/** Unknown wire enums fall back through the domain `fromWire` (never throws). */
fun Habits.toDomain(logs: Map<String, Int>): Habit = Habit(
    id = id, name = name, type = HabitType.fromWire(type), target = target.toInt(), unit = unit, icon = icon,
    timeOfDay = TimeOfDay.fromWire(timeOfDay), days = textToDays(days), reminder = reminder, goalId = goalId,
    gentle = gentle != 0L, archived = archived != 0L, createdAt = createdAt, logs = logs,
)

fun Habit.toRow(position: Long): Habits = Habits(
    id, name, type.wire, target.toLong(), unit, icon, timeOfDay.wire, daysToText(days), reminder, goalId,
    if (gentle) 1L else 0L, if (archived) 1L else 0L, createdAt, position,
)
