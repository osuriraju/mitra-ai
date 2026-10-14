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
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.errorJson
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TasksRepositoryImplTest {
    // 20:00 UTC on the 13th = 01:30 IST on the 14th: the device's "today" is 2026-10-14.
    private val clock = FixedClock(Instant.parse("2026-10-13T20:00:00Z"), TimeZone.of("Asia/Kolkata"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val api = ApiClient(server.engine, TEST_BASE, PersistentCookiesStorage(InMemorySecureStore(), clock))
    private val writer = OutboxWriter(db, api.json, clock)

    private class Env(val repo: TasksRepositoryImpl, val engine: SyncEngine, val prefs: PrefStore)

    private fun TestScope.env(): Env {
        val io = UnconfinedTestDispatcher(testScheduler)
        // Long debounce: tests drain explicitly.
        val engine = SyncEngine(api, db, {}, backgroundScope, io, debounceMs = 60_000)
        val prefs = PrefStore(db, io)
        prefs.put(PrefKeys.LAST_USER_ID, "u1")
        return Env(TasksRepositoryImpl(api, db, prefs, writer, engine, clock, io), engine, prefs)
    }

    private fun q(v: String?) = v?.let { "\"$it\"" } ?: "null"
    private fun taskJson(id: String, title: String = id, done: Boolean = false, due: String? = null, projectId: String? = null, parentId: String? = null) =
        """{"id":"$id","title":"$title","status":"${if (done) "done" else "todo"}","done":$done,"due":${q(due)},"projectId":${q(projectId)},"parentId":${q(parentId)},"notes":"","source":"user","createdAt":"2026-10-01T00:00:00.000Z"}"""
    private fun projectJson(id: String, name: String) = """{"id":"$id","name":"$name","emoji":"💼","createdAt":"2026-10-01T00:00:00.000Z"}"""
    private fun bootstrap(tasks: List<String>, projects: List<String> = emptyList()) =
        """{"tasks":[${tasks.joinToString(",")}],"projects":[${projects.joinToString(",")}]}"""

    private fun seed(vararg tasks: Task) = tasks.forEach { db.tasksQueries.upsert(it.toRow()) }
    private fun local(id: String) = db.tasksQueries.selectById(id).executeAsOneOrNull()?.toDomain()
    private fun ids() = db.tasksQueries.selectIds().executeAsList().sorted()
    private fun queue() = db.outboxQueries.selectAll().executeAsList()

    @Test fun refreshMirrorsTheServer() = runTest {
        val e = env()
        seed(Task("gone", "Deleted on the web"), Task("t1", "Stale title"))
        server.routes["GET /api/tasks/bootstrap"] = { json(bootstrap(listOf(taskJson("t1", "Fresh"), taskJson("t2")), listOf(projectJson("p1", "Work")))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(listOf("t1", "t2"), ids())
        assertEquals("Fresh", local("t1")!!.title)
        assertEquals(listOf(Project("p1", "Work", "💼", null, "2026-10-01T00:00:00.000Z")), e.repo.projects.first())
    }

    @Test fun refreshKeepsRowsWithPendingChanges() = runTest {
        val e = env()
        seed(Task("t1", "Server title"))
        server.offline = true
        e.repo.update("t1", listOf(TaskChange.Title("Local title")))
        val mine = e.repo.create(TaskDraft("Created offline"))
        server.offline = false
        server.routes["GET /api/tasks/bootstrap"] = { json(bootstrap(listOf(taskJson("t1", "Server title")))) }
        e.repo.refresh()
        assertEquals("Local title", local("t1")!!.title)
        assertNotNull(local(mine.id))
    }

    @Test fun refreshRacingAWriteIsDiscarded() = runTest {
        val e = env()
        seed(Task("t1", "Write report", due = "2026-10-14"))
        var gets = 0
        server.routes["PATCH /api/tasks/t1"] = { json(taskJson("t1", "Write report", done = true, due = "2026-10-14")) }
        server.routes["GET /api/tasks/bootstrap"] = {
            gets++
            if (gets == 1) {
                // While the first snapshot is being built the user completes the task and that PATCH is acknowledged.
                e.repo.setDone("t1", true)
                e.engine.drain()
                json(bootstrap(listOf(taskJson("t1", "Write report", done = false, due = "2026-10-14")))) // stale
            } else {
                json(bootstrap(listOf(taskJson("t1", "Write report", done = true, due = "2026-10-14"))))
            }
        }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(2, gets)
        assertTrue(local("t1")!!.done)
    }

    @Test fun refreshAfterSignOutWritesNothing() = runTest {
        val e = env()
        server.routes["GET /api/tasks/bootstrap"] = {
            e.prefs.remove(PrefKeys.LAST_USER_ID) // logout while the request is on the wire
            json(bootstrap(listOf(taskJson("t1"))))
        }
        e.repo.refresh()
        assertEquals(emptyList(), ids())
        assertIs<Outcome.Err>(e.repo.refresh()) // no account on the device: nothing is fetched
        assertEquals(1, server.log.size)
    }

    @Test fun createQueuesPostWithClientIdAndExplicitNulls() = runTest {
        val e = env()
        server.offline = true
        val t = e.repo.create(TaskDraft("  Call John  ", due = "2026-10-15", pri = Priority.High))
        assertEquals("Call John", t.title)
        assertEquals(t, local(t.id))
        val row = queue().single()
        assertEquals(Triple("POST", "tasks", t.id), Triple(row.method, row.path, row.entityId))
        assertEquals(
            """{"id":"${t.id}","title":"Call John","projectId":null,"goalId":null,"habitId":null,"due":"2026-10-15","time":null,"estMin":null,"pri":"high","status":"todo","done":false,"completedAt":null,"parentId":null,"notes":"","recurring":null,"amount":null,"someday":false,"source":"user"}""",
            row.body,
        )
    }

    @Test fun completeSendsIdempotentPatchWithLocalDate() = runTest {
        val e = env()
        seed(Task("t1", "Write", due = "2026-10-14"))
        server.offline = true
        assertNull(e.repo.setDone("t1", true))
        e.repo.setDone("t1", true) // second tap / widget + app: no new write
        val row = queue().single()
        assertEquals("PATCH" to "tasks/t1", row.method to row.path)
        assertEquals("""{"status":"done","done":true,"completedAt":"2026-10-14"}""", row.body)
        assertEquals("2026-10-14", local("t1")!!.completedAt)

        server.offline = false
        val bodies = mutableListOf<String>()
        server.routes["PATCH /api/tasks/t1"] = { req -> bodies += req.body.toByteArray().decodeToString(); json(taskJson("t1", "Write", done = true, due = "2026-10-14")) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(listOf("""{"status":"done","done":true,"completedAt":"2026-10-14"}"""), bodies)
        assertTrue(local("t1")!!.done)
    }

    @Test fun completingRecurringSpawnsTheNextOccurrenceOnce() = runTest {
        val e = env()
        seed(Task("rent", "Pay rent", due = "2026-01-31", recurring = "Monthly", pri = Priority.High))
        server.offline = true
        val nextId = assertNotNull(e.repo.setDone("rent", true))
        val next = local(nextId)!!
        assertEquals("2026-03-03", next.due)
        assertEquals("recurring", next.source)
        assertFalse(next.done)
        assertEquals(Priority.High, next.pri)

        e.repo.setDone("rent", false)
        assertNull(e.repo.setDone("rent", true)) // an open occurrence already exists
        assertEquals(listOf("PATCH tasks/rent", "POST tasks", "PATCH tasks/rent"), queue().map { "${it.method} ${it.path}" })
    }

    @Test fun moveIntoProjectCreatedOfflineReachesServerInOrder() = runTest {
        val e = env()
        seed(Task("t1", "Draft deck"))
        server.offline = true
        e.repo.update("t1", listOf(TaskChange.Title("Draft pitch deck")))
        val p = e.repo.createProject("Pitch", "🚀")
        e.repo.update("t1", listOf(TaskChange.ProjectId(p.id)))

        server.offline = false
        val created = mutableSetOf<String>()
        server.routes["POST /api/projects"] = { created += p.id; json(projectJson(p.id, "Pitch")) }
        server.routes["PATCH /api/tasks/t1"] = { req ->
            val sent = req.body.toByteArray().decodeToString()
            if (p.id in sent && p.id !in created) json(errorJson("NOT_FOUND", "Project not found"), HttpStatusCode.NotFound)
            else json(taskJson("t1", "Draft pitch deck", projectId = if (p.id in sent) p.id else null))
        }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(listOf("PATCH /api/tasks/t1", "POST /api/projects", "PATCH /api/tasks/t1"), server.log)
        assertEquals(p.id, local("t1")!!.projectId)
        assertEquals("Draft pitch deck", local("t1")!!.title)
    }

    @Test fun rejectedWriteRestoresTheServerCopy() = runTest {
        val e = env()
        seed(Task("t1", "Server title"))
        server.offline = true
        e.repo.update("t1", listOf(TaskChange.Title("Local title")))
        server.offline = false
        server.routes["PATCH /api/tasks/t1"] = { json(errorJson("NOT_FOUND", "Task not found"), HttpStatusCode.NotFound) }
        server.routes["GET /api/tasks/bootstrap"] = { json(bootstrap(listOf(taskJson("t1", "Server title")))) }
        e.engine.drain()
        assertEquals("Server title", local("t1")!!.title)
        assertEquals("Couldn't save · Task not found", e.engine.messages.first())
    }

    @Test fun deleteTakesSubtasksAndUndoRecreatesThem() = runTest {
        val e = env()
        seed(Task("p", "Parent"), Task("c", "Child", parentId = "p"), Task("other", "Other"))
        server.offline = true
        val removed = e.repo.delete("p")
        assertEquals(listOf("p", "c"), removed.map { it.id })
        assertEquals(listOf("other"), ids())

        // A refresh while the DELETE is still queued must not bring the parent or its subtask back.
        server.offline = false
        server.routes["GET /api/tasks/bootstrap"] = {
            json(bootstrap(listOf(taskJson("p", "Parent"), taskJson("c", "Child", parentId = "p"), taskJson("other", "Other"))))
        }
        e.repo.refresh()
        assertEquals(listOf("other"), ids())

        e.repo.restore(removed)
        assertEquals(listOf("c", "other", "p"), ids())
        assertEquals(listOf("DELETE tasks/p", "POST tasks", "POST tasks"), queue().map { "${it.method} ${it.path}" })
        assertEquals(listOf("p", "c"), queue().drop(1).map { it.entityId })
    }

    @Test fun deletingAProjectMovesItsTasksToTheInbox() = runTest {
        val e = env()
        db.projectsQueries.upsert(Project("p1", "Work").toRow())
        seed(Task("t1", "In project", projectId = "p1"))
        server.offline = true
        e.repo.deleteProject("p1")
        assertNull(local("t1")!!.projectId)
        assertEquals(emptyList(), e.repo.projects.first())
        assertEquals("DELETE projects/p1", queue().single().let { "${it.method} ${it.path}" })
    }

    @Test fun refreshWhileProjectDeleteIsQueuedKeepsItsTasksInTheInbox() = runTest {
        val e = env()
        db.projectsQueries.upsert(Project("p1", "Work").toRow())
        seed(Task("t1", "In project", projectId = "p1"))
        server.offline = true
        e.repo.deleteProject("p1")

        // The server still has the project and its task until the queued DELETE lands.
        server.offline = false
        server.routes["GET /api/tasks/bootstrap"] = {
            json(bootstrap(listOf(taskJson("t1", "In project", projectId = "p1"), taskJson("t2", "New on web", projectId = "p1")), listOf(projectJson("p1", "Work"))))
        }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(emptyList(), e.repo.projects.first())
        assertNull(local("t1")!!.projectId)
        assertNull(local("t2")!!.projectId)
    }

    @Test fun refreshThatKeepsLosingTheRaceReportsItAppliedNothing() = runTest {
        val e = env()
        seed(Task("t1", "Local"))
        server.routes["POST /api/tasks"] = { json("{}") }
        var gets = 0
        server.routes["GET /api/tasks/bootstrap"] = {
            gets++
            e.repo.create(TaskDraft("Typed while refreshing #$gets")) // every snapshot is overtaken by a local write
            json(bootstrap(emptyList()))
        }
        val r = e.repo.refresh()
        assertEquals(3, gets)
        // Not Ok: nothing was applied. The refresher refreshes again once the queue drains.
        assertEquals(TasksRepository.REFRESH_SUPERSEDED, assertIs<AppError.Server>(assertIs<Outcome.Err>(r).error).code)
        assertNotNull(local("t1"))
    }
}
