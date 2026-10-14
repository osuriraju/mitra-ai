package tech.beyondscale.mitra.data.repository

import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.Habit_logs
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.errorJson
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.ALL_DAYS
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitChange
import tech.beyondscale.mitra.domain.model.HabitDraft
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.TimeOfDay
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class HabitsRepositoryImplTest {
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00+05:30"), TimeZone.of("Asia/Kolkata"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val api = ApiClient(server.engine, TEST_BASE, PersistentCookiesStorage(InMemorySecureStore(), clock))
    private val writer = OutboxWriter(db, api.json, clock)

    private class Env(val repo: HabitsRepositoryImpl, val engine: SyncEngine, val prefs: PrefStore)

    private fun TestScope.env(): Env {
        val io = UnconfinedTestDispatcher(testScheduler)
        val engine = SyncEngine(api, db, {}, backgroundScope, io, debounceMs = 60_000)
        val prefs = PrefStore(db, io)
        prefs.put(PrefKeys.LAST_USER_ID, "u1")
        return Env(HabitsRepositoryImpl(api, db, prefs, writer, engine, clock, backgroundScope, io), engine, prefs)
    }

    /* ---------- fixtures ---------- */

    private fun habitJson(id: String, name: String = "Read", logs: Map<String, Int> = emptyMap(), archived: Boolean = false) =
        """{"id":"$id","name":"$name","type":"binary","target":1,"unit":"","icon":"book","timeOfDay":"anytime","days":[true,true,true,true,true,true,true],"gentle":true,""" +
            (if (archived) "\"archived\":true," else "") +
            """"createdAt":"2026-10-01T04:30:00.000Z","logs":{${logs.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}"""
    private fun list(vararg habits: String) = "[${habits.joinToString(",")}]"

    private fun seedHabit(id: String, name: String = "Read", position: Long = 0, logs: Map<String, Int> = emptyMap()) {
        db.habitsQueries.upsert(
            Habit(id, name, HabitType.Binary, 1, "", "book", TimeOfDay.Anytime, ALL_DAYS, null, null, true, false, "2026-10-01T04:30:00.000Z", emptyMap())
                .toRow(position),
        )
        logs.forEach { (d, v) -> db.habitLogsQueries.upsert(Habit_logs(id, d, v.toLong())) }
    }
    private fun logs(id: String) = db.habitLogsQueries.selectForHabit(id).executeAsList().associate { it.date to it.value_.toInt() }
    private fun habitIds() = db.habitsQueries.selectIds().executeAsList().sorted()
    private fun queue() = db.outboxQueries.selectAll().executeAsList()

    /* ---------- refresh ---------- */

    @Test fun refreshReplacesHabitsAndLogs() = runTest {
        val e = env()
        server.routes["GET /api/habits"] = { json(list(habitJson("h1", logs = mapOf("2026-10-13" to 1)), habitJson("h2", "Walk", archived = true))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        val habits = e.repo.habits.first()
        assertEquals(listOf("h1", "h2"), habits.map { it.id })
        assertEquals(mapOf("2026-10-13" to 1), habits[0].logs)
        assertTrue(habits[1].archived)

        server.routes["GET /api/habits"] = { json(list(habitJson("h2", "Walk", archived = true))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        // Read the tables: a second `first()` on the hot shared flow may still replay the earlier snapshot.
        assertEquals(listOf("h2"), habitIds())
        assertEquals(emptyMap(), logs("h1")) // no cascade in SQL: the repository drops the logs too
    }

    @Test fun refreshKeepsLogsOutsideWindow() = runTest {
        val e = env()
        seedHabit("h1", logs = mapOf("2025-08-01" to 1, "2026-10-01" to 1))
        server.routes["GET /api/habits"] = { json(list(habitJson("h1", logs = mapOf("2026-10-13" to 1)))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(mapOf("2025-08-01" to 1, "2026-10-13" to 1), logs("h1"))
    }

    @Test fun refreshKeepsPendingHabit() = runTest {
        val e = env()
        seedHabit("h1", "Read")
        server.offline = true
        val created = e.repo.create(HabitDraft(name = "Meditate"))
        e.repo.update("h1", listOf(HabitChange.Name("Read more")))
        server.offline = false
        server.routes["GET /api/habits"] = { json(list(habitJson("h1", "Read"))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        val habits = e.repo.habits.first().associateBy { it.id }
        assertNotNull(habits[created.id])
        assertEquals("Read more", habits["h1"]!!.name)
    }

    @Test fun refreshRacingAWriteIsDiscarded() = runTest {
        val e = env()
        var gets = 0
        var added: Habit? = null
        server.routes["GET /api/habits"] = {
            gets++
            if (gets == 1) added = e.repo.create(HabitDraft(name = "Stretch")) // written while the snapshot is on the wire
            json(list()) // stale: knows nothing of the new habit
        }
        val result = e.repo.refresh()
        assertTrue(result is Outcome.Ok || (result as Outcome.Err).error.let { it is AppError.Server && it.code == HabitsRepository.REFRESH_SUPERSEDED })
        assertEquals(listOf(added!!.id), habitIds())
    }

    @Test fun refreshAfterSignOutWritesNothing() = runTest {
        val e = env()
        server.routes["GET /api/habits"] = {
            e.prefs.remove(PrefKeys.LAST_USER_ID)
            json(list(habitJson("h1", logs = mapOf("2026-10-13" to 1))))
        }
        e.repo.refresh()
        assertEquals(emptyList(), habitIds())
        assertEquals(emptyList(), db.habitLogsQueries.selectAll().executeAsList())
    }

    /* ---------- writes ---------- */

    @Test fun createQueuesWebBodyAndAppendsAtTheEnd() = runTest {
        val e = env()
        seedHabit("h1", position = 4)
        server.offline = true
        val h = e.repo.create(HabitDraft(name = "  Water  ", type = HabitType.Count, target = 8, unit = "glasses", goalId = "g1"))
        assertEquals("Water", h.name)
        assertEquals("2026-10-14T04:30:00Z", h.createdAt)
        assertEquals(listOf("h1", h.id), e.repo.habits.first().map { it.id })
        assertEquals(5L, db.habitsQueries.selectById(h.id).executeAsOne().position)
        val row = queue().single()
        assertEquals(OutboxEntities.HABIT, row.entity)
        assertEquals(h.id, row.entityId)
        assertEquals("POST habits", "${row.method} ${row.path}")
        assertEquals(
            """{"id":"${h.id}","name":"Water","type":"count","target":8,"unit":"glasses","icon":"repeat","timeOfDay":"anytime","days":[true,true,true,true,true,true,true],"reminder":null,"goalId":"g1","gentle":true}""",
            row.body,
        )
    }

    @Test fun updatesCoalesceIntoOnePatch() = runTest {
        val e = env()
        seedHabit("h1")
        server.offline = true
        e.repo.update("h1", listOf(HabitChange.Name(" Read daily ")))
        e.repo.update("h1", listOf(HabitChange.Archived(true), HabitChange.Reminder(null)))
        e.repo.update("missing", listOf(HabitChange.Name("x")))
        e.repo.update("h1", emptyList())
        val h = e.repo.habits.first().single()
        assertEquals("Read daily", h.name)
        assertTrue(h.archived)
        val row = queue().single()
        assertEquals("PATCH habits/h1", "${row.method} ${row.path}")
        assertEquals("""{"name":"Read daily","archived":true,"reminder":null}""", row.body)
    }

    @Test fun deleteRemovesHabitAndLogs() = runTest {
        val e = env()
        seedHabit("h1", logs = mapOf("2026-10-13" to 1))
        server.offline = true
        e.repo.delete("h1")
        assertEquals(emptyList(), e.repo.habits.first())
        assertEquals(emptyMap(), logs("h1"))
        assertEquals(listOf("DELETE habits/h1"), queue().map { "${it.method} ${it.path}" })
        assertNull(queue().single().body)
    }

    @Test fun checkThenUncheckCoalescesIntoOnePut() = runTest {
        val e = env()
        seedHabit("h")
        server.offline = true
        e.repo.setLog("h", "2026-10-14", 1)
        assertEquals(mapOf("2026-10-14" to 1), e.repo.habits.first().single().logs)
        e.repo.setLog("h", "2026-10-14", null)
        val row = queue().single()
        assertEquals("PUT habits/h/logs", "${row.method} ${row.path}")
        assertEquals(OutboxEntities.HABIT_LOG, row.entity)
        assertEquals("h", row.entityId)
        assertEquals("""{"date":"2026-10-14","value":null}""", row.body)
        assertEquals(emptyMap(), logs("h"))
    }

    @Test fun zeroIsSentAsNullAndUnknownHabitIsIgnored() = runTest {
        val e = env()
        seedHabit("h", logs = mapOf("2026-10-14" to 3))
        server.offline = true
        e.repo.setLog("h", "2026-10-14", 0)
        e.repo.setLog("nope", "2026-10-14", 1)
        assertEquals(emptyMap(), logs("h"))
        assertEquals(listOf("""{"date":"2026-10-14","value":null}"""), queue().map { it.body })
    }

    @Test fun logsForDifferentDaysQueueSeparately() = runTest {
        val e = env()
        seedHabit("h")
        server.offline = true
        e.repo.setLog("h", "2026-10-13", 1)
        e.repo.setLog("h", "2026-10-14", 2)
        assertEquals(
            listOf("""{"date":"2026-10-13","value":1}""", """{"date":"2026-10-14","value":2}"""),
            queue().map { it.body },
        )
        assertEquals(mapOf("2026-10-13" to 1, "2026-10-14" to 2), logs("h"))
    }

    @Test fun echoDoesNotOverwritePendingLogs() = runTest {
        val e = env()
        seedHabit("h")
        server.offline = true
        e.repo.setLog("h", "2026-10-13", 1)
        e.repo.setLog("h", "2026-10-14", 1)
        server.offline = false
        var puts = 0
        server.routes["PUT /api/habits/h/logs"] = { req ->
            puts++
            if (puts == 1) {
                json(habitJson("h", logs = mapOf("2026-10-13" to 1))) // knows only the first day
            } else {
                // The first echo was skipped: the second day survived while its PUT was queued.
                assertEquals(mapOf("2026-10-13" to 1, "2026-10-14" to 1), logs("h"))
                assertTrue("\"2026-10-14\"" in req.body.toByteArray().decodeToString())
                json(habitJson("h", "Read (web)", logs = mapOf("2026-10-13" to 1, "2026-10-14" to 1)))
            }
        }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(2, puts)
        val h = e.repo.habits.first().single()
        assertEquals("Read (web)", h.name) // the last echo, nothing queued, is taken
        assertEquals(mapOf("2026-10-13" to 1, "2026-10-14" to 1), h.logs)
    }

    @Test fun echoKeepsPositionAndOldLogs() = runTest {
        val e = env()
        seedHabit("h", position = 3, logs = mapOf("2025-01-01" to 1, "2026-10-10" to 1))
        server.offline = true
        e.repo.update("h", listOf(HabitChange.Name("Read more")))
        server.offline = false
        server.routes["PATCH /api/habits/h"] = { json(habitJson("h", "Read more", logs = mapOf("2026-10-12" to 1))) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(3L, db.habitsQueries.selectById("h").executeAsOne().position)
        assertEquals(mapOf("2025-01-01" to 1, "2026-10-12" to 1), logs("h"))
    }

    @Test fun offlineCreateCheckDeleteReachesServerInOrder() = runTest {
        val e = env()
        server.offline = true
        val h = e.repo.create(HabitDraft(name = "Read"))
        e.repo.setLog(h.id, "2026-10-14", 1)
        e.repo.update(h.id, listOf(HabitChange.Name("Read more")))
        e.repo.delete(h.id)
        val rows = queue()
        assertEquals(
            listOf("POST habits", "PUT habits/${h.id}/logs", "PATCH habits/${h.id}", "DELETE habits/${h.id}"),
            rows.map { "${it.method} ${it.path}" },
        )
        assertTrue(rows.all { it.entityId == h.id })
        assertEquals(emptyList(), e.repo.habits.first())

        server.offline = false
        server.routes["POST /api/habits"] = { json(habitJson(h.id)) }
        server.routes["PUT /api/habits/${h.id}/logs"] = { json(habitJson(h.id, logs = mapOf("2026-10-14" to 1))) }
        server.routes["PATCH /api/habits/${h.id}"] = { json(habitJson(h.id, "Read more", logs = mapOf("2026-10-14" to 1))) }
        server.routes["DELETE /api/habits/${h.id}"] = { json("""{"ok":true}""") }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(
            listOf("POST /api/habits", "PUT /api/habits/${h.id}/logs", "PATCH /api/habits/${h.id}", "DELETE /api/habits/${h.id}"),
            server.log,
        )
        assertEquals(emptyList(), habitIds())
        assertEquals(emptyList(), db.habitLogsQueries.selectAll().executeAsList())
    }

    @Test fun echoDoesNotResurrectAHabitDeletedWhileInFlight() = runTest {
        val e = env()
        server.offline = true
        val h = e.repo.create(HabitDraft(name = "Read"))
        server.offline = false
        server.routes["POST /api/habits"] = {
            e.repo.delete(h.id)
            json(habitJson(h.id))
        }
        server.routes["DELETE /api/habits/${h.id}"] = { json("""{"ok":true}""") }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(emptyList(), habitIds())
    }

    @Test fun rejectedWriteRefreshes() = runTest {
        val e = env()
        seedHabit("h1")
        server.offline = true
        e.repo.setLog("h1", "2026-10-14", 1)
        server.offline = false
        server.routes["PUT /api/habits/h1/logs"] = { json(errorJson("NOT_FOUND", "Habit not found"), HttpStatusCode.NotFound) }
        server.routes["GET /api/habits"] = { json(list(habitJson("h2", "Walk"))) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(listOf("h2"), habitIds())
        assertEquals(emptyMap(), logs("h1"))
    }
}
