package tech.beyondscale.mitra.data.repository

import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.createBody
import tech.beyondscale.mitra.data.remote.errorJson
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.goals.applyProgress
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalDraft
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.MilestoneChange
import tech.beyondscale.mitra.domain.model.MilestoneDraft
import tech.beyondscale.mitra.domain.model.ProgressEntry
import tech.beyondscale.mitra.domain.model.ReviewEvery
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class GoalsRepositoryImplTest {
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00+05:30"), TimeZone.of("Asia/Kolkata"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val api = ApiClient(server.engine, TEST_BASE, PersistentCookiesStorage(InMemorySecureStore(), clock))
    private val writer = OutboxWriter(db, api.json, clock)

    private class Env(val repo: GoalsRepositoryImpl, val engine: SyncEngine, val prefs: PrefStore)

    private fun TestScope.env(repoClock: AppClock = clock): Env {
        val io = UnconfinedTestDispatcher(testScheduler)
        val engine = SyncEngine(api, db, {}, backgroundScope, io, debounceMs = 60_000)
        val prefs = PrefStore(db, io)
        prefs.put(PrefKeys.LAST_USER_ID, "u1")
        return Env(GoalsRepositoryImpl(api, db, prefs, writer, engine, repoClock, backgroundScope, io), engine, prefs)
    }

    /* ---------- fixtures ---------- */

    private val g1 = "60000000-0000-4000-8000-000000000001"
    private val g2 = "60000000-0000-4000-8000-000000000002"
    private val m1 = "70000000-0000-4000-8000-000000000001"
    private val m2 = "70000000-0000-4000-8000-000000000002"
    private val m3 = "70000000-0000-4000-8000-000000000003"
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    /** A ₹ goal at 50000 of 100000 with milestones at 25000 (reached), 60000 and 100000. */
    private val fundJson =
        """{"id":"$g1","name":"Emergency fund","type":"numeric","term":"Medium-term","due":"2027-03-31","startedOn":"2026-04-01",""" +
            """"current":50000,"target":100000,"unit":"₹","nextAction":"Move ₹5k","reviewEvery":"Monthly","reviewOn":"2026-11-01","status":"active",""" +
            """"notes":"","createdAt":"2026-04-01T04:00:00.000Z","milestones":[""" +
            """{"id":"$m1","title":"Quarter","target":25000,"reachedOn":"2026-06-01"},{"id":"$m2","title":"Most","target":60000},""" +
            """{"id":"$m3","title":"All","target":100000,"targetDate":"2027-03-31"}],""" +
            """"progressLog":[{"date":"2026-06-01","value":25000},{"date":"2026-09-01","value":50000,"note":"Salary"}]}"""

    private val fund = Goal(
        id = g1, name = "Emergency fund", type = GoalType.Numeric, term = GoalTerm.Medium, due = "2027-03-31", startedOn = "2026-04-01",
        current = 50000.0, target = 100000.0, unit = "₹", formula = null, nextAction = "Move ₹5k", reviewEvery = ReviewEvery.Monthly,
        reviewOn = "2026-11-01", state = GoalState.Active, notes = "", createdAt = "2026-04-01T04:00:00.000Z",
        milestones = listOf(
            Milestone(m1, "Quarter", 25000.0, null, "2026-06-01", null),
            Milestone(m2, "Most", 60000.0),
            Milestone(m3, "All", 100000.0, "2027-03-31"),
        ),
        progressLog = listOf(ProgressEntry("2026-06-01", 25000.0), ProgressEntry("2026-09-01", 50000.0, "Salary")),
    )

    private val runJson =
        """{"id":"$g2","name":"Run a 10k","type":"milestone","term":"Short-term","due":"2027-01-01","startedOn":"2026-10-01",""" +
            """"current":0,"target":0,"unit":"","formula":"x","nextAction":"","reviewEvery":"Weekly","reviewOn":"2026-10-08","status":"paused",""" +
            """"notes":"n","createdAt":"2026-10-01T04:00:00.000Z","milestones":[],"progressLog":[]}"""

    private val run = Goal(
        id = g2, name = "Run a 10k", type = GoalType.Milestone, term = GoalTerm.Short, due = "2027-01-01", startedOn = "2026-10-01",
        current = 0.0, target = 0.0, unit = "", formula = "x", nextAction = "", reviewEvery = ReviewEvery.Weekly, reviewOn = "2026-10-08",
        state = GoalState.Paused, notes = "n", createdAt = "2026-10-01T04:00:00.000Z", milestones = emptyList(), progressLog = emptyList(),
    )

    private fun list(vararg goals: String) = "[${goals.joinToString(",")}]"

    /** The wire shape of a goal (as `goalOut`), for echoes that should equal a local copy. */
    private fun Goal.wire(): String = JsonObject(
        createBody() + mapOf(
            "createdAt" to JsonPrimitive(createdAt),
            "progressLog" to buildJsonArray {
                progressLog.forEach { p -> add(buildJsonObject { put("date", p.date); put("value", p.value); p.note?.let { put("note", it) } }) }
            },
        ),
    ).toString()

    private suspend fun Env.seed(vararg goals: String) {
        server.routes["GET /api/goals"] = { json(list(*goals)) }
        assertEquals(Outcome.Ok(Unit), repo.refresh())
    }

    /** The shared flow's current value once the combine has caught up with the last write (it runs on the test scheduler). */
    private suspend fun TestScope.flowNow(e: Env): List<Goal> {
        testScheduler.runCurrent()
        return e.repo.goals.first()
    }

    private fun queue() = db.outboxQueries.selectAll().executeAsList()
    private fun local(id: String) = assembleLocal().firstOrNull { it.id == id }
    private fun assembleLocal() = tech.beyondscale.mitra.data.local.assembleGoals(
        db.goalsQueries.selectAll().executeAsList(), db.milestonesQueries.selectAll().executeAsList(), db.goalProgressQueries.selectAll().executeAsList(),
    )
    private fun childRows() = db.milestonesQueries.selectAll().executeAsList().size to db.goalProgressQueries.selectAll().executeAsList().size

    /* ---------- refresh ---------- */

    @Test fun refreshReplacesGoalsMilestonesAndProgress() = runTest {
        val e = env()
        e.seed(fundJson, runJson)
        assertEquals(listOf(fund, run), flowNow(e))

        server.routes["GET /api/goals"] = { json(list(runJson)) }
        assertEquals(Outcome.Ok(Unit), e.repo.refresh())
        assertEquals(listOf(run), flowNow(e))
        // No SQL cascade: the dropped goal's milestones and progress rows go with it.
        assertEquals(0 to 0, childRows())
    }

    @Test fun refreshKeepsPendingGoals() = runTest {
        val e = env()
        e.seed(fundJson)
        server.offline = true
        e.repo.update(g1, listOf(GoalChange.Name("Rainy day")))
        val created = e.repo.create(GoalDraft(name = "New", due = "2027-01-01"))
        server.offline = false
        server.routes["GET /api/goals"] = { json(list(fundJson)) }
        assertEquals(Outcome.Ok(Unit), e.repo.refresh())
        assertEquals("Rainy day", local(g1)!!.name)
        assertNotNull(local(created.id))
    }

    @Test fun refreshRacingAWriteIsDiscarded() = runTest {
        val e = env()
        var gets = 0
        var added: Goal? = null
        // While the test body waits on the mock's thread, runTest may skip ahead to the engine's debounced drain: its
        // POST must not be refused (a refusal drops the row from the outbox and the goal with it on the next attempt).
        server.routes["POST /api/goals"] = { throw Exception("network down") }
        server.routes["GET /api/goals"] = {
            gets++
            if (gets == 1) added = e.repo.create(GoalDraft(name = "Racing", due = "2027-01-01")) // written while the list is on the wire
            json(list(fundJson))
        }
        val result = e.repo.refresh()
        assertTrue(result is Outcome.Ok || (result as Outcome.Err).error.let { it is AppError.Server && it.code == GoalsRepository.REFRESH_SUPERSEDED })
        assertNotNull(local(added!!.id))
    }

    @Test fun refreshAfterSignOutWritesNothing() = runTest {
        val e = env()
        server.routes["GET /api/goals"] = {
            e.prefs.remove(PrefKeys.LAST_USER_ID)
            json(list(fundJson))
        }
        e.repo.refresh()
        assertEquals(emptyList(), db.goalsQueries.selectIds().executeAsList())
        assertEquals(0 to 0, childRows())
    }

    /* ---------- writes ---------- */

    @Test fun createQueuesTheFullBodyAndAppliesGoalMath() = runTest {
        val e = env()
        server.offline = true
        val g = e.repo.create(GoalDraft(name = " Save ", type = GoalType.Numeric, due = "2027-04-14", target = 1000.0, milestoneTitles = listOf("Half", "All")))
        assertEquals(listOf(g), flowNow(e))
        assertEquals(listOf(500.0, 1000.0), g.milestones.map { it.target })
        assertEquals("2026-11-13", g.reviewOn)
        val row = queue().single()
        assertEquals("POST goals", "${row.method} ${row.path}")
        assertEquals(OutboxEntities.GOAL, row.entity)
        assertEquals(g.id, row.entityId)
        assertEquals(g.createBody().toString(), row.body)
        assertTrue("\"name\":\"Save\"" in row.body!!)
        assertTrue(g.milestones.all { uuidV4.matches(it.id) } && uuidV4.matches(g.id))
    }

    @Test fun updateCoalescesAndDeleteRemovesChildRows() = runTest {
        val e = env()
        e.seed(fundJson)
        server.offline = true
        e.repo.update(g1, listOf(GoalChange.Name("A")))
        e.repo.update(g1, listOf(GoalChange.Formula(null), GoalChange.Target(120000.0)))
        assertEquals(listOf("""{"name":"A","formula":null,"target":120000.0}"""), queue().map { it.body })
        assertEquals("goal:$g1", queue().single().coalesceKey)
        assertEquals(120000.0, local(g1)!!.target)

        e.repo.delete(g1)
        assertNull(local(g1))
        assertEquals(0 to 0, childRows())
        assertEquals(listOf("PATCH goals/$g1", "DELETE goals/$g1"), queue().map { "${it.method} ${it.path}" })
        assertNull(queue().last().body)
    }

    @Test fun logProgressAppliesLocallyAndSendsLocalDate() = runTest {
        // 00:30 IST on 1 Nov is still 31 Oct in UTC: the device's day is sent.
        val e = env(FixedClock(Instant.parse("2026-11-01T00:30:00+05:30"), TimeZone.of("Asia/Kolkata")))
        e.seed(fundJson)
        server.offline = true
        e.repo.logProgress(g1, 8000.0, null)
        val g = local(g1)!!
        assertEquals(58000.0, g.current)
        assertEquals(ProgressEntry("2026-11-01", 58000.0, null), g.progressLog.last())
        assertNull(g.milestones[1].reachedOn) // 58000 < 60000
        val row = queue().single()
        assertEquals("POST goals/$g1/progress", "${row.method} ${row.path}")
        assertEquals(OutboxEntities.GOAL_PROGRESS, row.entity)
        assertEquals(g1, row.entityId)
        assertNull(row.coalesceKey)
        val body = api.json.parseToJsonElement(row.body!!).jsonObject
        assertEquals(listOf("id", "value", "note", "date"), body.keys.toList())
        assertTrue(uuidV4.matches(body["id"]!!.jsonPrimitive.content), row.body)
        assertTrue(row.body!!.endsWith(""""value":8000.0,"note":null,"date":"2026-11-01"}"""), row.body)
    }

    @Test fun echoEqualsLocalApplication() = runTest {
        val e = env()
        e.seed(fundJson)
        server.offline = true
        e.repo.logProgress(g1, 15000.0, " Bonus ")
        val before = flowNow(e)
        // Hand-built from goals.service.ts logProgress: numeric adds (50000 + 15000), "Most" (60000) is reached today,
        // the point is the new current with the trimmed note; 65000 < target, so the goal stays active.
        val echo =
            """{"id":"$g1","name":"Emergency fund","type":"numeric","term":"Medium-term","due":"2027-03-31","startedOn":"2026-04-01",""" +
                """"current":65000,"target":100000,"unit":"₹","nextAction":"Move ₹5k","reviewEvery":"Monthly","reviewOn":"2026-11-01","status":"active",""" +
                """"notes":"","createdAt":"2026-04-01T04:00:00.000Z","milestones":[""" +
                """{"id":"$m1","title":"Quarter","target":25000,"reachedOn":"2026-06-01"},{"id":"$m2","title":"Most","target":60000,"reachedOn":"2026-10-14"},""" +
                """{"id":"$m3","title":"All","target":100000,"targetDate":"2027-03-31"}],""" +
                """"progressLog":[{"date":"2026-06-01","value":25000},{"date":"2026-09-01","value":50000,"note":"Salary"},{"date":"2026-10-14","value":65000,"note":"Bonus"}]}"""
        server.offline = false
        server.routes["POST /api/goals/$g1/progress"] = { json(echo) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(before, flowNow(e))
        assertEquals(65000.0, before.single().current)
        assertEquals("2026-10-14", before.single().milestones[1].reachedOn)
    }

    @Test fun progressRowsNeverCoalesce() = runTest {
        val e = env()
        e.seed(fundJson)
        server.offline = true
        e.repo.logProgress(g1, 1000.0, null)
        e.repo.logProgress(g1, 1000.0, null)
        val rows = queue()
        assertEquals(2, rows.size)
        val ids = rows.map { api.json.parseToJsonElement(it.body!!).jsonObject["id"]!!.jsonPrimitive.content }
        assertNotEquals(ids[0], ids[1])
        assertEquals(listOf(51000.0, 52000.0), local(g1)!!.progressLog.takeLast(2).map { it.value })
    }

    @Test fun progressRetryReusesItsId() = runTest {
        val e = env()
        e.seed(fundJson)
        server.offline = true
        e.repo.logProgress(g1, 1000.0, null)
        server.offline = false
        val bodies = mutableListOf<String>()
        server.routes["POST /api/goals/$g1/progress"] = { req ->
            bodies += req.body.toByteArray().decodeToString()
            if (bodies.size == 1) throw Exception("connection reset") // landed on the server; the answer was lost
            // The server finds the id and answers with the goal as it stands, without applying the value again.
            json(local(g1)!!.wire())
        }
        assertEquals(SyncEngine.Result.Offline, e.engine.drain(scheduleRetryOnFailure = false))
        assertEquals(SyncEngine.Result.Drained, e.engine.drain(scheduleRetryOnFailure = false))
        assertEquals(2, bodies.size)
        assertEquals(bodies[0], bodies[1])
        assertEquals(51000.0, local(g1)!!.current)
    }

    @Test fun addUpdateAndDeleteMilestoneQueueGoalScopedRows() = runTest {
        val e = env()
        e.seed(runJson)
        server.offline = true
        assertNull(e.repo.addMilestone("unknown", MilestoneDraft("X")))
        val ms = assertNotNull(e.repo.addMilestone(g2, MilestoneDraft(" 5k ", targetDate = "2026-11-01")))
        assertEquals(1.0, local(g2)!!.target) // milestone-type: target = milestone count
        e.repo.updateMilestone(g2, ms.id, listOf(MilestoneChange.ReachedOn("2026-10-14")))
        e.repo.updateMilestone(g2, ms.id, listOf(MilestoneChange.Notes("felt good")))
        val g = local(g2)!!
        assertEquals(1.0, g.current)
        assertEquals(listOf(ProgressEntry("2026-10-14", 1.0, "5k")), g.progressLog)
        e.repo.deleteMilestone(g2, ms.id)
        assertEquals(0.0, local(g2)!!.target)
        val rows = queue()
        assertEquals(
            listOf("POST goals/$g2/milestones", "PATCH goals/$g2/milestones/${ms.id}", "DELETE goals/$g2/milestones/${ms.id}"),
            rows.map { "${it.method} ${it.path}" },
        )
        assertEquals("""{"id":"${ms.id}","title":"5k","target":null,"targetDate":"2026-11-01","reachedOn":null,"notes":null}""", rows[0].body)
        assertEquals("""{"reachedOn":"2026-10-14","notes":"felt good"}""", rows[1].body)
        assertEquals("milestone:${ms.id}", rows[1].coalesceKey)
        assertTrue(rows.all { it.entity == OutboxEntities.MILESTONE && it.entityId == g2 })
    }

    /* ---------- echo / refresh rules ---------- */

    @Test fun goalEchoSkippedWhileGoalHasQueuedRows() = runTest {
        val e = env()
        e.seed(runJson)
        server.offline = true
        e.repo.update(g2, listOf(GoalChange.Notes("later")))
        val ms = assertNotNull(e.repo.addMilestone(g2, MilestoneDraft("Lap")))
        server.offline = false
        // The PATCH lands; the server copy doesn't have the milestone yet, whose POST is still queued (503 → retry).
        server.routes["PATCH /api/goals/$g2"] = { json(runJson.replace("\"notes\":\"n\"", "\"notes\":\"later\"")) }
        server.routes["POST /api/goals/$g2/milestones"] = { json(errorJson("UNAVAILABLE", "Down"), HttpStatusCode.ServiceUnavailable) }
        assertEquals(SyncEngine.Result.Retry, e.engine.drain(scheduleRetryOnFailure = false))
        assertEquals(listOf(ms), local(g2)!!.milestones)
        assertEquals("later", local(g2)!!.notes)
    }

    @Test fun deletedMilestoneNotReintroduced() = runTest {
        val e = env()
        e.seed(fundJson)
        server.offline = true
        e.repo.update(g1, listOf(GoalChange.NextAction("Move ₹10k")))
        e.repo.deleteMilestone(g1, m2)
        server.offline = false
        server.routes["PATCH /api/goals/$g1"] = { json(fundJson.replace("Move ₹5k", "Move ₹10k")) } // still has m2
        server.routes["DELETE /api/goals/$g1/milestones/$m2"] = { json(errorJson("UNAVAILABLE", "Down"), HttpStatusCode.ServiceUnavailable) }
        assertEquals(SyncEngine.Result.Retry, e.engine.drain(scheduleRetryOnFailure = false))
        assertEquals(listOf(m1, m3), local(g1)!!.milestones.map { it.id })

        server.routes["GET /api/goals"] = { json(list(fundJson)) }
        assertEquals(Outcome.Ok(Unit), e.repo.refresh())
        assertEquals(listOf(m1, m3), local(g1)!!.milestones.map { it.id })
        assertEquals("Move ₹10k", local(g1)!!.nextAction)
    }

    @Test fun deletedGoalNotResurrected() = runTest {
        val e = env()
        server.offline = true
        val g = e.repo.create(GoalDraft(name = "Gone", due = "2027-01-01", milestoneTitles = listOf("A")))
        server.offline = false
        server.routes["POST /api/goals"] = {
            e.repo.delete(g.id) // deleted while the create is on the wire
            json(g.wire())
        }
        server.routes["DELETE /api/goals/${g.id}"] = { json("""{"ok":true}""") }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(listOf("POST /api/goals", "DELETE /api/goals/${g.id}"), server.log)
        assertEquals(emptyList(), flowNow(e))
        assertEquals(0 to 0, childRows())
    }

    @Test fun offlineGoalSequenceReachesServerInOrder() = runTest {
        val e = env()
        server.offline = true
        val g = e.repo.create(GoalDraft(name = "Save", due = "2027-04-14", target = 100.0, milestoneTitles = listOf("One", "Two")))
        val third = assertNotNull(e.repo.addMilestone(g.id, MilestoneDraft("Three", target = 90.0)))
        e.repo.updateMilestone(g.id, third.id, listOf(MilestoneChange.ReachedOn("2026-10-14")))
        e.repo.logProgress(g.id, 10.0, "first")
        val mid2 = g.milestones[1].id
        e.repo.deleteMilestone(g.id, mid2)
        val final = local(g.id)!!
        assertEquals(listOf(g.milestones[0].id, third.id), final.milestones.map { it.id })
        assertTrue(queue().all { it.entityId == g.id })

        server.offline = false
        listOf(
            "POST /api/goals", "POST /api/goals/${g.id}/milestones", "PATCH /api/goals/${g.id}/milestones/${third.id}",
            "POST /api/goals/${g.id}/progress", "DELETE /api/goals/${g.id}/milestones/$mid2",
        ).forEach { route -> server.routes[route] = { json(final.wire()) } }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(
            listOf(
                "POST /api/goals", "POST /api/goals/${g.id}/milestones", "PATCH /api/goals/${g.id}/milestones/${third.id}",
                "POST /api/goals/${g.id}/progress", "DELETE /api/goals/${g.id}/milestones/$mid2",
            ),
            server.log,
        )
        assertEquals(listOf(final), flowNow(e))
    }

    @Test fun rejectedProgressRefreshesServerCopy() = runTest {
        val e = env()
        e.seed(fundJson)
        server.offline = true
        e.repo.logProgress(g1, 5000.0, null)
        assertEquals(fund.applyProgress(5000.0, null, "2026-10-14"), local(g1))
        server.offline = false
        server.routes["POST /api/goals/$g1/progress"] = { json(errorJson("ID_CONFLICT", "That progress id is already in use"), HttpStatusCode.Conflict) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(listOf(fund), flowNow(e))
    }

    @Test fun writesToAnUnknownGoalAreNoOps() = runTest {
        val e = env()
        e.repo.update("nope", listOf(GoalChange.Name("x")))
        e.repo.logProgress("nope", 1.0, null)
        e.repo.updateMilestone("nope", "m", listOf(MilestoneChange.Title("x")))
        e.repo.deleteMilestone("nope", "m")
        e.repo.delete("nope")
        assertEquals(emptyList(), queue())
    }
}
