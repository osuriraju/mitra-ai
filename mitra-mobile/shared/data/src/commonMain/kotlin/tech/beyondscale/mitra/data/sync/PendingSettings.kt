package tech.beyondscale.mitra.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.remote.UserDto

object OutboxEntities {
    const val USER_SETTINGS = "user-settings"
    const val TASK = "task"
    const val PROJECT = "project"
    const val TRANSACTION = "transaction"
    const val CATEGORY = "category"
    const val RECURRING = "recurring"
    const val SUBSCRIPTION = "subscription"
    /** "Mark paid" action rows (`POST money/recurring/:id/pay`, `entityId` = the recurring id). */
    const val RECURRING_PAY = "recurring-pay"
    /** Habit rows (create/patch/delete), `entityId` = the habit id. */
    const val HABIT = "habit"
    /** `PUT habits/:id/logs` rows, `entityId` = the habit id. */
    const val HABIT_LOG = "habit-log"
    /** Goal rows (create/patch/delete), `entityId` = the goal id. */
    const val GOAL = "goal"
    /** `POST goals/:id/progress` action rows, `entityId` = the goal id. */
    const val GOAL_PROGRESS = "goal-progress"
    /** Milestone add/patch/delete rows, `entityId` = the goal id (pending is decided per goal). */
    const val MILESTONE = "milestone"
    /** `PUT wellness/entries/:date` rows, `entityId` = the ISO day; coalesce per day (`wellness-entry:<date>`). */
    const val WELLNESS_ENTRY = "wellness-entry"
    /** `PATCH wellness/settings` rows, `entityId` = null; coalesce on one key (`wellness-settings`). */
    const val WELLNESS_SETTINGS = "wellness-settings"
    /** Note create/patch/delete rows, `entityId` = the note id; editor autosave coalesces PATCHes per note. */
    const val NOTE = "note"
    /** `POST ai/actions` rows, `entityId` = the local action id (server assigns its own — see foundation notes). */
    const val AI_ACTION = "ai-action"
    /** `POST ai/actions/:id/revert` rows, `entityId` = the action id. */
    const val AI_REVERT = "ai-revert"
}

/**
 * Re-applies queued (and in-flight) settings patches, in `seq` order, to a server copy of the user, using the same
 * shallow merge the server does. Keeps an offline toggle from reverting when a fresher-looking server copy is adopted.
 * Call inside the transaction that writes the result.
 */
fun UserDto.withPendingSettings(db: MitraDb, json: Json): UserDto {
    val rows = db.outboxQueries.selectAll().executeAsList().filter { it.entity == OutboxEntities.USER_SETTINGS }
    if (rows.isEmpty()) return this
    var merged = settings
    for (row in rows) {
        val patch = row.body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: continue
        merged = JsonObject(merged + patch)
    }
    return copy(settings = merged)
}
