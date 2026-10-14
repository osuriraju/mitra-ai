package tech.beyondscale.mitra.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tech.beyondscale.mitra.domain.model.NoteChange
import tech.beyondscale.mitra.domain.model.NoteDraft

/* Server limits (notes.service.ts zod): title 1..200 trimmed, body ≤ 200 000, folder 1..40 trimmed, ≤ 30 tags of 1..40. */
const val NOTE_TITLE_MAX = 200
const val NOTE_BODY_MAX = 200_000
const val NOTE_FOLDER_MAX = 40
const val NOTE_TAG_MAX = 40
const val NOTE_TAGS_MAX = 30
const val UNTITLED_NOTE = "Untitled"

fun cleanNoteTags(tags: List<String>): List<String> =
    tags.map { it.trim().take(NOTE_TAG_MAX).trim() }.filter { it.isNotEmpty() }.distinct().take(NOTE_TAGS_MAX)

/**
 * What the server would accept and echo: trimmed and capped. A blank title or folder is dropped (the server would
 * reject it with 400, and a rejection refreshes the note back to its server copy — losing the body typed with it),
 * so an autosave while the title field is momentarily empty keeps the last title.
 */
fun normalizedNote(changes: List<NoteChange>): List<NoteChange> = changes.mapNotNull { c ->
    when (c) {
        is NoteChange.Title -> c.v.trim().take(NOTE_TITLE_MAX).trim().takeIf { it.isNotEmpty() }?.let { NoteChange.Title(it) }
        is NoteChange.Body -> NoteChange.Body(c.v.take(NOTE_BODY_MAX))
        is NoteChange.Folder -> c.v.trim().take(NOTE_FOLDER_MAX).trim().takeIf { it.isNotEmpty() }?.let { NoteChange.Folder(it) }
        is NoteChange.Tags -> NoteChange.Tags(cleanNoteTags(c.v))
        is NoteChange.Mood -> NoteChange.Mood(c.v?.coerceIn(0, 4))
        else -> c
    }
}

/** A draft the server will accept: blank title → "Untitled", blank folder → "Personal". */
fun normalizedNote(draft: NoteDraft): NoteDraft = draft.copy(
    title = draft.title.trim().take(NOTE_TITLE_MAX).trim().ifEmpty { UNTITLED_NOTE },
    body = draft.body.take(NOTE_BODY_MAX),
    folder = draft.folder.trim().take(NOTE_FOLDER_MAX).trim().ifEmpty { "Personal" },
    tags = cleanNoteTags(draft.tags),
    mood = draft.mood?.coerceIn(0, 4),
)

/** `PATCH notes/:id` body: only the changed keys; `JournalDate(null)` / `Mood(null)` send JSON null. */
fun notePatch(changes: List<NoteChange>): JsonObject = buildJsonObject {
    normalizedNote(changes).forEach { c ->
        when (c) {
            is NoteChange.Title -> put("title", c.v)
            is NoteChange.Body -> put("body", c.v)
            is NoteChange.Folder -> put("folder", c.v)
            is NoteChange.Tags -> putJsonArray("tags") { c.v.forEach { add(JsonPrimitive(it)) } }
            is NoteChange.JournalDate -> put("journalDate", c.v)
            is NoteChange.Ai -> put("ai", c.v)
            is NoteChange.Mood -> put("mood", c.v)
        }
    }
}
