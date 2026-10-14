package tech.beyondscale.mitra.data.repository

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskStatus

/** Applies field edits locally and builds the matching `PATCH tasks/:id` body (null = clear on the server). */
internal fun applyChanges(old: Task, changes: List<TaskChange>, today: String): Pair<Task, JsonObject> {
    var t = old
    val patch = linkedMapOf<String, JsonElement>()
    for (c in changes) when (c) {
        is TaskChange.Title -> c.value.trim().let { t = t.copy(title = it); patch["title"] = JsonPrimitive(it) }
        is TaskChange.ProjectId -> { t = t.copy(projectId = c.value); patch["projectId"] = JsonPrimitive(c.value) }
        is TaskChange.Due -> { t = t.copy(due = c.value); patch["due"] = JsonPrimitive(c.value) }
        is TaskChange.Time -> { t = t.copy(time = c.value); patch["time"] = JsonPrimitive(c.value) }
        is TaskChange.EstMin -> { t = t.copy(estMin = c.value); patch["estMin"] = JsonPrimitive(c.value) }
        is TaskChange.Pri -> { t = t.copy(pri = c.value); patch["pri"] = JsonPrimitive(c.value?.wire) }
        is TaskChange.Status -> {
            val done = c.value == TaskStatus.Done
            val completedAt = if (!done) null else if (t.done) t.completedAt ?: today else today
            t = t.copy(status = c.value, done = done, completedAt = completedAt)
            patch["status"] = JsonPrimitive(c.value.wire)
            patch["done"] = JsonPrimitive(done)
            patch["completedAt"] = JsonPrimitive(completedAt)
        }
        is TaskChange.Notes -> { t = t.copy(notes = c.value); patch["notes"] = JsonPrimitive(c.value) }
        is TaskChange.Recurring -> { t = t.copy(recurring = c.value); patch["recurring"] = JsonPrimitive(c.value) }
        is TaskChange.Someday -> { t = t.copy(someday = c.value); patch["someday"] = JsonPrimitive(c.value) }
        is TaskChange.Parent -> { t = t.copy(parentId = c.value); patch["parentId"] = JsonPrimitive(c.value) }
    }
    return t to JsonObject(patch)
}

/**
 * Complete / reopen. Always sends status + done + completedAt together so a coalesced earlier status change can't
 * leave the server with `done: true, status: blocked`; idempotent when resent. `completedAt` is the local date.
 */
internal fun completion(old: Task, done: Boolean, today: String): Pair<Task, JsonObject> {
    val status = when {
        done -> TaskStatus.Done
        old.status == TaskStatus.Done -> TaskStatus.Todo
        else -> old.status
    }
    val completedAt = if (done) today else null
    return old.copy(done = done, status = status, completedAt = completedAt) to buildJsonObject {
        put("status", status.wire)
        put("done", done)
        put("completedAt", completedAt)
    }
}
