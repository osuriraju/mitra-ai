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

    @Test fun doesNotCoalesceAcrossLaterRows() {
        // Patch task, create a project, patch the task again: folding the 2nd patch into the 1st would send it
        // before the project exists on the server.
        writer.enqueue("task", "PATCH", "tasks/t1", buildJsonObject { put("title", "A") }, "task:t1", "t1")
        writer.enqueue("project", "POST", "projects", buildJsonObject { put("id", "p1") }, entityId = "p1")
        writer.enqueue("task", "PATCH", "tasks/t1", buildJsonObject { put("projectId", "p1") }, "task:t1", "t1")
        val rows = db.outboxQueries.selectAll().executeAsList()
        assertEquals(listOf("tasks/t1", "projects", "tasks/t1"), rows.map { it.path })
        assertEquals("""{"projectId":"p1"}""", rows.last().body)
    }

    @Test fun recordsEntityId() {
        writer.enqueue("task", "DELETE", "tasks/t9", null, entityId = "t9")
        assertEquals("t9", db.outboxQueries.selectAll().executeAsList().single().entityId)
        assertEquals(listOf("t9"), db.outboxQueries.pendingEntityIds("task").executeAsList())
    }
}
