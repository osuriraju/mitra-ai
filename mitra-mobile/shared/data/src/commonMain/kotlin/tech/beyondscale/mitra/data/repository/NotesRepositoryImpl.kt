package tech.beyondscale.mitra.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.newId
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.db.Outbox
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.shareForRepository
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.NoteDto
import tech.beyondscale.mitra.data.remote.createBody
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncHandler
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.NoteChange
import tech.beyondscale.mitra.domain.model.NoteDraft
import tech.beyondscale.mitra.domain.model.applyChanges
import tech.beyondscale.mitra.domain.model.toNote
import tech.beyondscale.mitra.domain.repository.NotesRepository

/**
 * Notes and journal entries, offline-first (the `HabitsRepositoryImpl` skeleton). Every outbox row of a note carries
 * `entityId = noteId`, so a note is "pending" while any of its rows is queued: no echo or refresh overwrites it, and
 * none resurrects it once deleted. Editor autosave calls [update] per keystroke burst; PATCHes coalesce per note
 * (`note:<id>`) while queued, so only the merged patch is sent. The server's PATCH has no version check (last write
 * wins) and its timestamps are days, so `sortKey` keeps "most recently edited first" exact on the device.
 */
class NotesRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val prefs: PrefStore,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val clock: AppClock,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : NotesRepository {
    /** Bumped before every local write and every applied server echo — see `TasksRepositoryImpl.writes`. */
    private val writes = MutableStateFlow(0L)

    override val notes: Flow<List<Note>> =
        db.notesQueries.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }.shareForRepository(scope)

    init {
        engine.register(
            NOTE,
            object : SyncHandler {
                override suspend fun onSuccess(response: JsonElement?) {
                    val dto = decode(response) ?: return // DELETE answers { ok: true }
                    writes.update { it + 1 }
                    withContext(io) { db.transaction { echo(dto) } }
                }
                // 404 (deleted on the web) or a 400 the editor let through: the server copy decides.
                override suspend fun onRejected(error: AppError) { refresh() }
                override suspend fun onRejected(error: AppError, row: Outbox) { refresh() }
            },
        )
    }

    /* ---------- reads ---------- */

    override suspend fun refresh(): Outcome<Unit> {
        repeat(REFRESH_ATTEMPTS) {
            val owner = withContext(io) { prefs.get(PrefKeys.LAST_USER_ID) } ?: return Outcome.Err(AppError.Unauthenticated())
            val seen = writes.value to engine.acknowledged.value
            val body = when (val r = api.call<List<NoteDto>>(HttpMethod.Get, "notes")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val settled = withContext(io) {
                db.transactionWithResult {
                    when {
                        // Signed out or switched account while the request was out: this data belongs to nobody here.
                        prefs.get(PrefKeys.LAST_USER_ID) != owner -> true
                        (writes.value to engine.acknowledged.value) != seen -> false
                        else -> { applyList(body); true }
                    }
                }
            }
            if (settled) return Outcome.Ok(Unit)
        }
        // Still racing writes: applied nothing. ModuleRefresherImpl refreshes once more after the drain that follows.
        return Outcome.Err(AppError.Server(409, NotesRepository.REFRESH_SUPERSEDED, "Your changes are still syncing. Try again in a moment."))
    }

    /** Server list (updatedAt desc) → local table, except notes with queued rows (kept as they are, or kept deleted). */
    private fun applyList(body: List<NoteDto>) {
        val pending = pendingIds()
        val server = body.map { it.id }.toSet()
        db.notesQueries.selectIds().executeAsList().filter { it !in server && it !in pending }.forEach { db.notesQueries.delete(it) }
        body.forEachIndexed { i, dto ->
            if (dto.id !in pending) db.notesQueries.upsert(dto.toDomain().toRow((body.size - i).toLong()))
        }
    }

    /* ---------- writes ---------- */

    override suspend fun create(draft: NoteDraft): Note {
        val n = normalizedNote(draft).toNote(newId(), clock.today())
        write { insert(n) }
        return n
    }

    override suspend fun update(id: String, changes: List<NoteChange>) {
        val clean = normalizedNote(changes)
        if (clean.isEmpty()) return
        write {
            val row = db.notesQueries.selectById(id).executeAsOneOrNull() ?: return@write
            val next = row.toDomain().applyChanges(clean, clock.today())
            db.notesQueries.upsert(next.toRow(db.notesQueries.nextSortKey().executeAsOne()))
            outbox.enqueue(NOTE, "PATCH", "notes/$id", notePatch(clean), coalesceKey = "note:$id", entityId = id)
        }
    }

    override suspend fun delete(id: String) {
        write {
            if (db.notesQueries.selectById(id).executeAsOneOrNull() == null) return@write
            db.notesQueries.delete(id)
            outbox.enqueue(NOTE, "DELETE", "notes/$id", null, entityId = id)
        }
    }

    override suspend fun restore(note: Note) {
        write {
            if (db.notesQueries.selectById(note.id).executeAsOneOrNull() != null) return@write
            insert(note)
        }
    }

    /* ---------- helpers ---------- */

    /** New (or restored) row on top and its POST. In a transaction. */
    private fun insert(n: Note) {
        db.notesQueries.upsert(n.toRow(db.notesQueries.nextSortKey().executeAsOne()))
        outbox.enqueue(NOTE, "POST", "notes", n.createBody(), entityId = n.id)
    }

    /** Local change + outbox row in one transaction. The counter is bumped first (see [writes]). */
    private suspend fun write(block: () -> Unit) {
        writes.update { it + 1 }
        withContext(io) { db.transaction { block() } }
        engine.requestSync()
    }

    /** Server copy after an acknowledged write: only for a note still here with nothing else queued. In a transaction. */
    private fun echo(dto: NoteDto) {
        val local = db.notesQueries.selectById(dto.id).executeAsOneOrNull() ?: return // deleted locally: no resurrection
        if (dto.id in pendingIds()) return // newer keystrokes are still queued: the local text wins
        db.notesQueries.upsert(dto.toDomain().toRow(local.sortKey))
    }

    private fun pendingIds(): Set<String> =
        db.outboxQueries.pendingEntityIds(NOTE).executeAsList().filter { it.isNotEmpty() }.toSet()

    private fun decode(element: JsonElement?): NoteDto? =
        (element as? JsonObject)?.let { runCatching { api.json.decodeFromJsonElement(NoteDto.serializer(), it) }.getOrNull() }

    companion object {
        const val NOTE = OutboxEntities.NOTE
        private const val REFRESH_ATTEMPTS = 3
    }
}
