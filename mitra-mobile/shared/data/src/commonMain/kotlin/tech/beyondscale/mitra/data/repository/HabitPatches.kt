package tech.beyondscale.mitra.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tech.beyondscale.mitra.domain.model.HabitChange

/**
 * The server's zod `.trim()` on name, unit and icon, applied before the change is stored locally and sent, so the
 * local row equals what the server will echo.
 */
fun normalized(changes: List<HabitChange>): List<HabitChange> = changes.map { c ->
    when (c) {
        is HabitChange.Name -> HabitChange.Name(c.v.trim())
        is HabitChange.Unit -> HabitChange.Unit(c.v.trim())
        is HabitChange.Icon -> HabitChange.Icon(c.v.trim())
        else -> c
    }
}

/** `PATCH habits/:id` body: only the changed keys; `Reminder(null)` / `GoalId(null)` send JSON null. */
fun habitPatch(changes: List<HabitChange>): JsonObject = buildJsonObject {
    normalized(changes).forEach { c ->
        when (c) {
            is HabitChange.Name -> put("name", c.v)
            is HabitChange.Type -> put("type", c.v.wire)
            is HabitChange.Target -> put("target", c.v)
            is HabitChange.Unit -> put("unit", c.v)
            is HabitChange.Icon -> put("icon", c.v)
            is HabitChange.TimeOfDay -> put("timeOfDay", c.v.wire)
            is HabitChange.Days -> putJsonArray("days") { c.v.forEach { add(JsonPrimitive(it)) } }
            is HabitChange.Reminder -> put("reminder", c.v)
            is HabitChange.GoalId -> put("goalId", c.v)
            is HabitChange.Gentle -> put("gentle", c.v)
            is HabitChange.Archived -> put("archived", c.v)
        }
    }
}
