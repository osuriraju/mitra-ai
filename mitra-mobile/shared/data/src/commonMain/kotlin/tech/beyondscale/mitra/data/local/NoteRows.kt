package tech.beyondscale.mitra.data.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tech.beyondscale.mitra.data.db.Notes
import tech.beyondscale.mitra.domain.model.Note

private val noteTags = ListSerializer(String.serializer())

/*
 * No SQL cascades: a note has no child rows. Deleting a note is just `notesQueries.delete(id)`; outbox rows for it
 * (entity NOTE, entityId = id) are the sync engine's concern.
 */

fun Notes.toDomain(): Note = Note(
    id, title, body, folder, runCatching { Json.decodeFromString(noteTags, tags) }.getOrDefault(emptyList()),
    journalDate, ai != 0L, mood?.toInt(), createdAt, updatedAt,
)

/** [sortKey] orders notes edited on the same day (higher = more recent); see Notes.sq. */
fun Note.toRow(sortKey: Long): Notes = Notes(
    id, title, body, folder, Json.encodeToString(noteTags, tags), journalDate, if (ai) 1L else 0L, mood?.toLong(),
    createdAt, updatedAt, sortKey,
)
