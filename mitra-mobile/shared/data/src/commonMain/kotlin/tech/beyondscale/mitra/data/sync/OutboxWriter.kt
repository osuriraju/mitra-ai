package tech.beyondscale.mitra.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.newId
import tech.beyondscale.mitra.data.db.MitraDb

class OutboxWriter(private val db: MitraDb, private val json: Json, private val clock: AppClock) {
    /**
     * Queues a server write. Call inside the same `db.transaction {}` as the local change it mirrors.
     * With a [coalesceKey], a newer patch is shallow-merged into a queued (not in-flight) one if it is the newest row —
     * the server merges settings/patches shallowly too, so the result is identical.
     * Use a key only for independent last-write-wins resources; never for writes whose order or
     * count matters (creates, deletes, actions).
     * [entityId] marks which record the row writes, so refreshes can keep that record's local copy until the row is acknowledged.
     */
    fun enqueue(entity: String, method: String, path: String, body: JsonObject?, coalesceKey: String? = null, entityId: String? = null) {
        if (coalesceKey != null) {
            val queued = db.outboxQueries.pendingByCoalesceKey(coalesceKey).executeAsOneOrNull()
            // Merge only into the newest row: folding into an older one would send this change before the writes
            // queued after it (e.g. a task moved into a project whose POST is still queued).
            if (queued != null && queued.seq == db.outboxQueries.lastSeq().executeAsOneOrNull()) {
                val old = queued.body?.let { json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
                val merged = JsonObject(old + (body ?: JsonObject(emptyMap())))
                db.outboxQueries.updateBody(json.encodeToString(JsonObject.serializer(), merged), queued.id)
                return
            }
        }
        db.outboxQueries.insert(
            id = newId(), entity = entity, method = method, path = path,
            body = body?.let { json.encodeToString(JsonObject.serializer(), it) },
            coalesceKey = coalesceKey, createdAt = clock.now().toEpochMilliseconds(), entityId = entityId,
        )
    }
}
