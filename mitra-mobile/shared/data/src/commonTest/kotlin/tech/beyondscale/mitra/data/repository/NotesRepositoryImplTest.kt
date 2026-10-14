package tech.beyondscale.mitra.data.repository

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
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.NoteChange
import tech.beyondscale.mitra.domain.model.NoteDraft
import tech.beyondscale.mitra.domain.repository.NotesRepository
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class NotesRepositoryImplTest {
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00+05:30"), TimeZone.of("Asia/Kolkata"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val api = ApiClient(server.engine, TEST_BASE, PersistentCookiesStorage(InMemorySecureStore(), clock))
    private val writer = OutboxWriter(db, api.json, clock)

    private class Env(val repo: NotesRepositoryImpl, val engine: SyncEngine, val prefs: PrefStore)

    private fun TestScope.env(): Env {
        val io = UnconfinedTestDispatcher(testScheduler)
        val engine = SyncEngine(api, db, {}, backgroundScope, io, debounceMs = 60_000)
        val prefs = PrefStore(db, io)
        prefs.put(PrefKeys.LAST_USER_ID, "u1")
        return Env(NotesRepositoryImpl(api, db, prefs, writer, engine, clock, backgroundScope, io), engine, prefs)
    }

    private fun noteJson(id: String, title: String = "Ideas", body: String = "", updatedAt: String = "2026-10-13", journalDate: String? = null, ai: Boolean = false) =
        """{"id":"$id","title":"$title","body":"$body","folder":"Ideas","tags":["x"],"journalDate":${journalDate?.let { "\"$it\"" } ?: "null"},""" +
            (if (ai) "\"ai\":true," else "") + """"createdAt":"2026-10-01","updatedAt":"$updatedAt"}"""
    private fun list(vararg notes: String) = "[${notes.joinToString(",")}]"

    private fun seed(id: String, title: String = "Ideas", body: String = "", sortKey: Long = 1) =
        db.notesQueries.upsert(Note(id, title, body, "Ideas", listOf("x"), null, false, null, "2026-10-01", "2026-10-13").toRow(sortKey))
    private fun ids() = db.notesQueries.selectAll().executeAsList().map { it.id }
    private fun note(id: String) = db.notesQueries.selectById(id).executeAsOneOrNull()?.toDomain()
    private fun queue() = db.outboxQueries.selectAll().executeAsList()

    /* ---------- refresh ---------- */

    @Test fun refreshKeepsServerOrderAndDropsMissing() = runTest {
        val e = env()
        seed("gone")
        server.routes["GET /api/notes"] = {
            json(list(noteJson("n1", updatedAt = "2026-10-13", journalDate = "2026-10-13", ai = true), noteJson("n2", "Work", updatedAt = "2026-10-13"), noteJson("n3", updatedAt = "2026-10-01")))
        }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        val notes = e.repo.notes.first()
        assertEquals(listOf("n1", "n2", "n3"), notes.map { it.id })
        assertEquals("2026-10-13", notes[0].journalDate)
        assertTrue(notes[0].ai)
        assertEquals(listOf("x"), notes[1].tags)
    }

    @Test fun refreshKeepsPendingNotes() = runTest {
        val e = env()
        seed("n1", body = "old")
        server.offline = true
        val created = e.repo.create(NoteDraft("Fresh"))
        e.repo.update("n1", listOf(NoteChange.Body("typed offline")))
        server.offline = false
        server.routes["GET /api/notes"] = { json(list(noteJson("n1", body = "server"))) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals("typed offline", note("n1")!!.body)
        assertEquals(listOf("n1", created.id), ids()) // edited last, so on top
    }

    @Test fun refreshRacingAWriteIsDiscarded() = runTest {
        val e = env()
        var gets = 0
        var added: Note? = null
        // runTest may skip ahead to the engine's debounced drain while the body waits: don't refuse its POST.
        server.routes["POST /api/notes"] = { throw Exception("network down") }
        server.routes["GET /api/notes"] = {
            gets++
            if (gets == 1) added = e.repo.create(NoteDraft("Written mid-refresh"))
            json(list())
        }
        val result = e.repo.refresh()
        assertTrue(result is Outcome.Ok || (result as Outcome.Err).error.let { it is AppError.Server && it.code == NotesRepository.REFRESH_SUPERSEDED })
        assertEquals(listOf(added!!.id), ids())
    }

    @Test fun refreshAfterSignOutWritesNothing() = runTest {
        val e = env()
        server.routes["GET /api/notes"] = {
            e.prefs.remove(PrefKeys.LAST_USER_ID)
            json(list(noteJson("n1")))
        }
        e.repo.refresh()
        assertEquals(emptyList(), ids())
    }

    /* ---------- writes ---------- */

    @Test fun createQueuesPostWithClientIdAndNormalizes() = runTest {
        val e = env()
        seed("n1", sortKey = 7)
        server.offline = true
        val n = e.repo.create(NoteDraft("  ", folder = " ", tags = listOf(" a ", "", "a", "b"), journalDate = "2026-10-14", mood = 9))
        assertEquals("Untitled", n.title)
        assertEquals("Personal", n.folder)
        assertEquals(listOf("a", "b"), n.tags)
        assertEquals("2026-10-14", n.createdAt)
        assertEquals(listOf(n.id, "n1"), ids())
        val row = queue().single()
        assertEquals(OutboxEntities.NOTE, row.entity)
        assertEquals(n.id, row.entityId)
        assertEquals("POST notes", "${row.method} ${row.path}")
        assertEquals(
            """{"id":"${n.id}","title":"Untitled","body":"","folder":"Personal","tags":["a","b"],"journalDate":"2026-10-14","ai":false,"mood":4}""",
            row.body,
        )
    }

    @Test fun autosavePatchesCoalescePerNote() = runTest {
        val e = env()
        seed("n1", sortKey = 1)
        seed("n2", sortKey = 2)
        server.offline = true
        e.repo.update("n1", listOf(NoteChange.Body("h")))
        e.repo.update("n1", listOf(NoteChange.Body("hello"), NoteChange.Title("  Hi ")))
        e.repo.update("n1", listOf(NoteChange.Title("   "))) // blank title is never sent (the server would 400)
        e.repo.update("n1", listOf(NoteChange.Mood(null), NoteChange.JournalDate(null)))
        e.repo.update("missing", listOf(NoteChange.Body("x")))
        val n = note("n1")!!
        assertEquals("Hi", n.title)
        assertEquals("hello", n.body)
        assertEquals("2026-10-14", n.updatedAt)
        assertEquals(listOf("n1", "n2"), ids())
        val row = queue().single()
        assertEquals("PATCH notes/n1", "${row.method} ${row.path}")
        assertEquals("""{"body":"hello","title":"Hi","mood":null,"journalDate":null}""", row.body)
    }

    @Test fun deleteThenRestoreRecreatesWithSameId() = runTest {
        val e = env()
        seed("n1", body = "keep me")
        server.offline = true
        val before = note("n1")!!
        e.repo.delete("n1")
        assertEquals(emptyList(), ids())
        e.repo.restore(before)
        e.repo.restore(before) // already back: no second POST
        assertEquals("keep me", note("n1")!!.body)
        assertEquals(listOf("DELETE notes/n1", "POST notes"), queue().map { "${it.method} ${it.path}" })
        assertNull(queue().first().body)
        assertTrue(queue().last().body!!.contains("\"id\":\"n1\""))
    }

    /* ---------- echoes ---------- */

    @Test fun echoDoesNotOverwriteNewerKeystrokes() = runTest {
        val e = env()
        seed("n1")
        server.offline = true
        e.repo.update("n1", listOf(NoteChange.Body("first")))
        server.offline = false
        var patches = 0
        server.routes["PATCH /api/notes/n1"] = {
            patches++
            if (patches == 1) {
                e.repo.update("n1", listOf(NoteChange.Body("first and more"))) // typed while the PATCH is in flight
                json(noteJson("n1", body = "first"))
            } else {
                json(noteJson("n1", "Ideas (server)", body = "first and more", updatedAt = "2026-10-14"))
            }
        }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(2, patches)
        val n = note("n1")!!
        assertEquals("first and more", n.body)
        assertEquals("Ideas (server)", n.title) // the last echo, nothing queued, is taken
    }

    @Test fun echoDoesNotResurrectANoteDeletedWhileInFlight() = runTest {
        val e = env()
        server.offline = true
        val n = e.repo.create(NoteDraft("Temp"))
        server.offline = false
        server.routes["POST /api/notes"] = {
            e.repo.delete(n.id)
            json(noteJson(n.id, "Temp"))
        }
        server.routes["DELETE /api/notes/${n.id}"] = { json("""{"ok":true}""") }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(emptyList(), ids())
    }

    @Test fun rejectedPatchRefreshes() = runTest {
        val e = env()
        seed("n1")
        server.offline = true
        e.repo.update("n1", listOf(NoteChange.Body("edit")))
        server.offline = false
        server.routes["PATCH /api/notes/n1"] = { json(errorJson("NOT_FOUND", "Note not found"), HttpStatusCode.NotFound) }
        server.routes["GET /api/notes"] = { json(list(noteJson("n2", "Other"))) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(listOf("n2"), ids())
    }
}
