package tech.beyondscale.mitra.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class OutboxWriterTest {
    private val db = MitraDb(testDriver())
    private val writer = OutboxWriter(db, Json, FixedClock(Instant.parse("2026-10-13T04:30:00Z")))

    @Test fun coalescesPatchesWithSameKeyShallowly() {
        writer.enqueue("user-settings", "PATCH", "users/me/settings", buildJsonObject { put("compact", true); put("digest", true) }, "user-settings")
        writer.enqueue("user-settings", "PATCH", "users/me/settings", buildJsonObject { put("digest", false) }, "user-settings")
        val rows = db.outboxQueries.selectAll().executeAsList()
        assertEquals(1, rows.size)
        assertEquals("""{"compact":true,"digest":false}""", rows.single().body)
    }

    @Test fun doesNotMergeIntoRowAlreadyInFlight() {
        writer.enqueue("user-settings", "PATCH", "users/me/settings", buildJsonObject { put("digest", true) }, "user-settings")
        db.outboxQueries.markInFlight(db.outboxQueries.selectNext().executeAsOne().id)
        writer.enqueue("user-settings", "PATCH", "users/me/settings", buildJsonObject { put("digest", false) }, "user-settings")
        assertEquals(listOf("""{"digest":true}""", """{"digest":false}"""), db.outboxQueries.selectAll().executeAsList().map { it.body })
    }

    @Test fun keepsInsertionOrderWithoutKey() {
        writer.enqueue("a", "POST", "x", null)
        writer.enqueue("b", "DELETE", "y", null)
        assertEquals(listOf("a", "b"), db.outboxQueries.selectAll().executeAsList().map { it.entity })
    }
}
