package tech.beyondscale.mitra.domain.model

/* Note models — wire shapes as mitra-backend/src/notes/notes.service.ts (noteOut). */

val NOTE_FOLDERS: List<String> = listOf("Work", "Ideas", "Learning", "Journal", "Personal")

/** Folder → icon name (web `FOLDER_ICON`); anything else uses "folder". */
val NOTE_FOLDER_ICON: Map<String, String> =
    mapOf("Work" to "folder", "Ideas" to "folder", "Learning" to "book", "Journal" to "edit", "Personal" to "user")

data class Note(
    val id: String,
    val title: String,
    val body: String = "",
    val folder: String = "Personal",
    val tags: List<String> = emptyList(),
    /** Set for journal entries — one per ISO day. */
    val journalDate: String? = null,
    val ai: Boolean = false,
    val mood: Int? = null,
    /** ISO day ("2026-10-14") — the server sends days, not instants. */
    val createdAt: String = "",
    /** ISO day; local edits stamp the device's today. */
    val updatedAt: String = "",
)

/**
 * What the new-note form / journal produces; the repository assigns the id. Server limits: title 1..200 (trimmed),
 * body ≤ 200 000, folder 1..40, ≤ 30 tags of 1..40 chars, mood 0..4.
 */
data class NoteDraft(
    val title: String,
    val body: String = "",
    val folder: String = "Personal",
    val tags: List<String> = emptyList(),
    val journalDate: String? = null,
    val ai: Boolean = false,
    val mood: Int? = null,
)

/** One field of `PATCH notes/:id`. */
sealed interface NoteChange {
    data class Title(val v: String) : NoteChange
    data class Body(val v: String) : NoteChange
    data class Folder(val v: String) : NoteChange
    data class Tags(val v: List<String>) : NoteChange
    data class JournalDate(val v: String?) : NoteChange
    data class Ai(val v: Boolean) : NoteChange
    data class Mood(val v: Int?) : NoteChange
}

fun NoteDraft.toNote(id: String, today: String): Note =
    Note(id, title.trim(), body, folder, tags, journalDate, ai, mood, createdAt = today, updatedAt = today)

/** A PATCH applied locally; any edit bumps `updatedAt` to [today] (web `updateNote`). */
fun Note.applyChanges(changes: List<NoteChange>, today: String): Note = changes.fold(copy(updatedAt = today)) { n, c ->
    when (c) {
        is NoteChange.Title -> n.copy(title = c.v)
        is NoteChange.Body -> n.copy(body = c.v)
        is NoteChange.Folder -> n.copy(folder = c.v)
        is NoteChange.Tags -> n.copy(tags = c.v)
        is NoteChange.JournalDate -> n.copy(journalDate = c.v)
        is NoteChange.Ai -> n.copy(ai = c.v)
        is NoteChange.Mood -> n.copy(mood = c.v)
    }
}
