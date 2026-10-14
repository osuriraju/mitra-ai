package tech.beyondscale.mitra.data.repository

import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.toDomain
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
import tech.beyondscale.mitra.domain.model.AiActionDraft
import tech.beyondscale.mitra.domain.model.AiProposal
import tech.beyondscale.mitra.domain.model.AiWho
import tech.beyondscale.mitra.domain.model.RevertOp
import tech.beyondscale.mitra.domain.model.SuggestionAction
import tech.beyondscale.mitra.domain.model.SuggestionState
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class AiRepositoryImplTest {
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00+05:30"), TimeZone.of("Asia/Kolkata"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val api = ApiClient(server.engine, TEST_BASE, PersistentCookiesStorage(InMemorySecureStore(), clock))
    private val writer = OutboxWriter(db, api.json, clock)

    private class Env(val repo: AiRepositoryImpl, val engine: SyncEngine, val prefs: PrefStore)

    private fun TestScope.env(): Env {
        val io = UnconfinedTestDispatcher(testScheduler)
        val engine = SyncEngine(api, db, {}, backgroundScope, io, debounceMs = 60_000)
        val prefs = PrefStore(db, io)
        prefs.put(PrefKeys.LAST_USER_ID, "u1")
        return Env(AiRepositoryImpl(api, db, prefs, writer, engine, clock, backgroundScope, io), engine, prefs)
    }

    private fun actionJson(id: String, action: String = "Created task", reverted: Boolean = false, revert: String? = null) =
        """{"id":"$id","when":"Today 10:00 AM","at":"2026-10-14T04:30:00.000Z","action":"$action","detail":"Call mom","state":"Approved by you","tone":"success","revertible":true,"reverted":$reverted""" +
            (revert?.let { ",\"revert\":$it" } ?: "") + "}"

    private fun actions() = db.aiActionsQueries.selectAll().executeAsList().map { it.toDomain() }
    private fun messages() = db.aiMessagesQueries.selectAll().executeAsList().map { it.toDomain() }
    private fun queue() = db.outboxQueries.selectAll().executeAsList()
    private fun bodyOf(text: String) = Json.parseToJsonElement(text).jsonObject

    /* ---------- refresh ---------- */

    @Test fun refreshReadsStatusAndLog() = runTest {
        val e = env()
        server.routes["GET /api/ai/status"] = { json("""{"enabled":false}""") }
        server.routes["GET /api/ai/actions"] = { json("[${actionJson("a1", revert = """[{"op":"deleteTask","id":"t1"}]""")}]") }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals("false", e.prefs.get(AiRepositoryImpl.AI_ENABLED))
        val a = actions().single()
        assertEquals(listOf(RevertOp(RevertOp.DELETE_TASK, "t1")), a.revert)
        assertEquals("2026-10-14T04:30:00.000Z", a.at)
    }

    @Test fun refreshKeepsLocalLogWhileWritesAreQueued() = runTest {
        val e = env()
        server.offline = true
        val logged = e.repo.logAction(AiActionDraft("Created task", "Call mom"))
        server.offline = false
        server.routes["GET /api/ai/status"] = { json("""{"enabled":true}""") }
        server.routes["GET /api/ai/actions"] = { json("[]") }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(listOf(logged.id), actions().map { it.id })
    }

    /* ---------- log + revert ---------- */

    @Test fun logActionSendsClientIdAndEchoReplacesRow() = runTest {
        val e = env()
        var sent: String? = null
        server.routes["POST /api/ai/actions"] = { req ->
            sent = req.body.toByteArray().decodeToString()
            val id = bodyOf(sent!!)["id"]!!.jsonPrimitive.content
            json(actionJson(id))
        }
        val logged = e.repo.logAction(
            AiActionDraft("Created task", "Call mom", revertible = true, revert = listOf(RevertOp(RevertOp.DELETE_TASK, "t1"))),
        )
        assertEquals(OutboxEntities.AI_ACTION, queue().single().entity)
        e.engine.drain()
        val body = bodyOf(sent!!)
        assertEquals(logged.id, body["id"]!!.jsonPrimitive.content)
        assertEquals("deleteTask", body["revert"]!!.jsonArray[0].jsonObject["op"]!!.jsonPrimitive.content)
        assertEquals(listOf(logged.id), actions().map { it.id })
        assertEquals("2026-10-14T04:30:00.000Z", actions().single().at) // server copy
        assertTrue(queue().isEmpty())
    }

    @Test fun revertBeforeCreateSyncsUsesSameIdInOrder() = runTest {
        val e = env()
        val logged = e.repo.logAction(
            AiActionDraft("Created task", "Call mom", revertible = true, revert = listOf(RevertOp(RevertOp.DELETE_TASK, "t1"))),
        )
        val ops = e.repo.revert(logged.id)
        assertEquals(listOf(RevertOp(RevertOp.DELETE_TASK, "t1")), ops)
        assertEquals(emptyList(), e.repo.revert(logged.id)) // already reverted: ops run once
        val local = actions()
        assertEquals(2, local.size)
        assertEquals("Reverted", local.first { it.id != logged.id }.action)
        assertTrue(local.first { it.id == logged.id }.reverted)
        assertEquals(listOf("ai/actions", "ai/actions/${logged.id}/revert"), queue().map { it.path })

        server.routes["POST /api/ai/actions"] = { json(actionJson(logged.id)) }
        server.routes["POST /api/ai/actions/${logged.id}/revert"] = {
            json("""{"original":${actionJson(logged.id, reverted = true)},"reverted":${actionJson("r1", "Reverted")},"revert":[]}""")
        }
        server.routes["GET /api/ai/status"] = { json("""{"enabled":true}""") }
        server.routes["GET /api/ai/actions"] = { json("[${actionJson("r1", "Reverted")},${actionJson(logged.id, reverted = true)}]") }
        e.engine.drain()
        assertEquals(
            listOf("POST /api/ai/actions", "POST /api/ai/actions/${logged.id}/revert", "GET /api/ai/status", "GET /api/ai/actions"),
            server.log,
        )
        assertEquals(setOf("r1", logged.id), actions().map { it.id }.toSet())
    }

    @Test fun rejectedLogRefreshes() = runTest {
        val e = env()
        server.routes["POST /api/ai/actions"] = { json(errorJson("ID_CONFLICT", "Id in use"), HttpStatusCode.Conflict) }
        server.routes["GET /api/ai/status"] = { json("""{"enabled":true}""") }
        server.routes["GET /api/ai/actions"] = { json("[]") }
        e.repo.logAction(AiActionDraft("Created task"))
        e.engine.drain()
        assertTrue(actions().isEmpty())
    }

    /* ---------- chat ---------- */

    @Test fun askSendsHistoryAndStoresReplyWithProposal() = runTest {
        val e = env()
        var sent: String? = null
        server.routes["POST /api/ai/ask"] = { req ->
            sent = req.body.toByteArray().decodeToString()
            json("""{"text":"Add it?","chart":[1,2],"proposal":{"kind":"task","title":"Call John","sub":"Tomorrow","taskTitle":"Call John","due":"2026-10-15"}}""")
        }
        val r = e.repo.ask("  Remind me to call John  ")
        assertIs<Outcome.Ok<*>>(r)
        val body = bodyOf(sent!!)
        assertEquals("Remind me to call John", body["text"]!!.jsonPrimitive.content)
        assertEquals("user", body["history"]!!.jsonArray.last().jsonObject["who"]!!.jsonPrimitive.content)
        val ms = messages()
        assertEquals(listOf(AiWho.User, AiWho.Ai), ms.map { it.who })
        assertEquals(listOf(1.0, 2.0), ms[1].chart)
        assertIs<AiProposal.Task>(ms[1].proposal)

        e.repo.setApplied(ms[1].id, true)
        assertTrue(messages()[1].applied)
        e.repo.cancelProposal(ms[1].id)
        assertEquals("Add it? (cancelled)", messages()[1].text)
        e.repo.clearConversation()
        assertTrue(messages().isEmpty())
    }

    @Test fun askOfflineAppendsSorry() = runTest {
        val e = env()
        server.offline = true
        val r = e.repo.ask("Hi")
        assertEquals(AppError.Offline, (r as Outcome.Err).error)
        assertEquals("Sorry — You appear to be offline", messages().last().text)
    }

    /* ---------- suggestions + journal ---------- */

    @Test fun suggestionsKeepLocalStateOfKnownIds() = runTest {
        val e = env()
        server.routes["GET /api/ai/suggestions"] = {
            json("""[{"id":"s1","ic":"bell","t":"Remind","p":"why","src":"Habits","tone":"accent","preview":"x","action":{"kind":"pause_goal","goalId":"g1"}},{"id":"s2","t":"Two"}]""")
        }
        assertIs<Outcome.Ok<Unit>>(e.repo.loadSuggestions())
        e.repo.setSuggestionState("s1", SuggestionState.Dismissed)
        assertIs<Outcome.Ok<Unit>>(e.repo.loadSuggestions(refresh = true))
        assertEquals("refresh=1", server.requests.last().url.encodedQuery)
        val list = db.suggestionsQueries.selectAll().executeAsList().map { it.toDomain() }
        assertEquals(listOf(SuggestionState.Dismissed, SuggestionState.Open), list.map { it.state })
        assertEquals(SuggestionAction.PauseGoal("g1"), list[0].action)
    }

    @Test fun journalDraftPostsDate() = runTest {
        val e = env()
        server.routes["POST /api/ai/journal-draft"] = { json("""{"text":"Calm.","sources":"tasks"}""") }
        val r = e.repo.journalDraft("2026-10-14")
        assertEquals("Calm.", (r as Outcome.Ok).value.text)
        assertFalse(server.requests.isEmpty())
    }
}
