package tech.beyondscale.mitra.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.NoteChange
import tech.beyondscale.mitra.domain.model.NoteDraft
import tech.beyondscale.mitra.domain.model.applyChanges
import tech.beyondscale.mitra.domain.model.toNote
import tech.beyondscale.mitra.domain.repository.NotesRepository

/** In-memory [NotesRepository]: new and edited notes move to the top and are stamped [today]. */
class FakeNotesRepository(initial: List<Note> = emptyList(), var today: String = "2026-10-14") : NotesRepository {
    val noteFlow = MutableStateFlow(initial)
    override val notes: Flow<List<Note>> = noteFlow
    val calls = mutableListOf<String>()
    var refreshResult: Outcome<Unit> = Outcome.Ok(Unit)
    private var seq = 0

    override suspend fun refresh(): Outcome<Unit> { calls += "refresh"; return refreshResult }

    override suspend fun create(draft: NoteDraft): Note {
        val n = draft.toNote("note${++seq}", today)
        calls += "create:${n.title}"
        noteFlow.update { listOf(n) + it }
        return n
    }

    override suspend fun update(id: String, changes: List<NoteChange>) {
        calls += "update:$id"
        noteFlow.update { list ->
            val n = list.firstOrNull { it.id == id } ?: return@update list
            listOf(n.applyChanges(changes, today)) + list.filter { it.id != id }
        }
    }

    override suspend fun delete(id: String) {
        calls += "delete:$id"
        noteFlow.update { list -> list.filter { it.id != id } }
    }

    override suspend fun restore(note: Note) {
        calls += "restore:${note.id}"
        noteFlow.update { list -> listOf(note) + list.filter { it.id != note.id } }
    }
}
