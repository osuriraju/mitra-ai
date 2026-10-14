package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.NoteChange
import tech.beyondscale.mitra.domain.model.NoteDraft

/** Notes and journal entries, offline-first. */
interface NotesRepository {
    /** Most recently edited first (server order: updatedAt desc; local edits move a note to the top). */
    val notes: Flow<List<Note>>

    /** Re-reads `notes`; notes with queued changes are kept. See [TasksRepository.refresh] for [REFRESH_SUPERSEDED]. */
    suspend fun refresh(): Outcome<Unit>

    suspend fun create(draft: NoteDraft): Note

    /**
     * Applies at once locally; the PATCH coalesces per note in the outbox, so the editor can call this on every
     * keystroke (autosave) and only the merged patch is sent. Unknown id = no-op.
     */
    suspend fun update(id: String, changes: List<NoteChange>)

    suspend fun delete(id: String)

    /** Undo of [delete]: re-creates [note] with its original id. */
    suspend fun restore(note: Note)

    companion object {
        const val REFRESH_SUPERSEDED = TasksRepository.REFRESH_SUPERSEDED
    }
}
