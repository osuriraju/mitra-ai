package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tech.beyondscale.mitra.domain.model.ALL_DAYS
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.TimeOfDay

/*
 * Shape of mitra-backend/src/habits/habits.service.ts `habitOut`: `reminder` and `goalId` are absent when null,
 * `archived` is absent when false, `logs` holds the last 400 days (server UTC) as { "YYYY-MM-DD": value }.
 * `id` and `name` have no default, so a DELETE's `{ ok: true }` never decodes as a habit.
 */
@Serializable
data class HabitDto(
    val id: String,
    val name: String,
    val type: String = "binary",
    val target: Int = 1,
    val unit: String = "",
    val icon: String = "repeat",
    val timeOfDay: String = "anytime",
    /** Mon..Sun. */
    val days: List<Boolean> = ALL_DAYS,
    val reminder: String? = null,
    val goalId: String? = null,
    val gentle: Boolean = true,
    val archived: Boolean = false,
    val createdAt: String = "",
    val logs: Map<String, Int> = emptyMap(),
)

/** Unknown wire enums fall back to the first constant; anything but 7 days reads as every day. */
fun HabitDto.toDomain(): Habit = Habit(
    id = id, name = name, type = HabitType.fromWire(type), target = target, unit = unit, icon = icon,
    timeOfDay = TimeOfDay.fromWire(timeOfDay), days = days.takeIf { it.size == 7 } ?: ALL_DAYS,
    reminder = reminder, goalId = goalId, gentle = gentle, archived = archived, createdAt = createdAt, logs = logs,
)

/** `POST habits` body as the web's `addHabit`: every field present, unset reminder / goal as JSON null. */
fun Habit.createBody(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("type", type.wire)
    put("target", target)
    put("unit", unit)
    put("icon", icon)
    put("timeOfDay", timeOfDay.wire)
    putJsonArray("days") { days.forEach { add(JsonPrimitive(it)) } }
    put("reminder", reminder)
    put("goalId", goalId)
    put("gentle", gentle)
}
