package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tech.beyondscale.mitra.domain.model.Note

/*
 * Shape of mitra-backend/src/notes/notes.service.ts `noteOut`: `ai` is absent when false, `mood` absent when null,
 * `journalDate` is null for plain notes, created/updated are ISO days. `id` and `title` have no default, so a
 * DELETE's `{ ok: true }` never decodes as a note.
 */
@Serializable
data class NoteDto(
    val id: String,
    val title: String,
    val body: String = "",
    val folder: String = "Personal",
    val tags: List<String> = emptyList(),
    val journalDate: String? = null,
    val ai: Boolean = false,
    val mood: Int? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

fun NoteDto.toDomain(): Note = Note(id, title, body, folder, tags, journalDate, ai, mood, createdAt, updatedAt)

/** `POST notes` body with the client id (so a restore re-creates the same note); null journalDate / mood sent as null. */
fun Note.createBody(): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    put("body", body)
    put("folder", folder)
    putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
    put("journalDate", journalDate)
    put("ai", ai)
    put("mood", mood)
}
