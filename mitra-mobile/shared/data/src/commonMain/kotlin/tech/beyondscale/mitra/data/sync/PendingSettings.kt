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
