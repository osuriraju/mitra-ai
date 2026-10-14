package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskStatus
import kotlin.math.roundToLong

/** Shape of `taskOut()` in mitra-backend/src/tasks/mapper.ts (undefined fields are simply absent). */
@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val projectId: String? = null,
    val goalId: String? = null,
    val habitId: String? = null,
    val due: String? = null,
    val time: String? = null,
    val estMin: Int? = null,
    val pri: String? = null,
    val status: String = "todo",
    val done: Boolean = false,
    val completedAt: String? = null,
    val parentId: String? = null,
    val notes: String = "",
    val recurring: String? = null,
    /** Rupees. */
    val amount: Double? = null,
    val someday: Boolean? = null,
    val source: String = "user",
    val createdAt: String = "",
)

@Serializable
data class ProjectDto(val id: String, val name: String, val emoji: String = "🚀", val goalId: String? = null, val createdAt: String = "")

@Serializable
data class TasksBootstrapDto(val tasks: List<TaskDto> = emptyList(), val projects: List<ProjectDto> = emptyList())

fun TaskDto.toDomain(): Task = Task(
    id = id, title = title, projectId = projectId, goalId = goalId, habitId = habitId, due = due, time = time,
    estMin = estMin, pri = Priority.fromWire(pri), status = TaskStatus.fromWire(status), done = done,
    completedAt = completedAt, parentId = parentId, notes = notes, recurring = recurring,
    amountPaise = amount?.let { (it * 100).roundToLong() }, someday = someday == true, source = source, createdAt = createdAt,
)

fun ProjectDto.toDomain(): Project = Project(id, name, emoji, goalId, createdAt)

/** `POST tasks` body: every field present, unset ones as JSON null (the web's `nulls()`). */
fun Task.createBody(): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    put("projectId", projectId)
    put("goalId", goalId)
    put("habitId", habitId)
    put("due", due)
    put("time", time)
    put("estMin", estMin)
    put("pri", pri?.wire)
    put("status", status.wire)
    put("done", done)
    put("completedAt", completedAt)
    put("parentId", parentId)
    put("notes", notes)
    put("recurring", recurring)
    put("amount", amountPaise?.let { it / 100.0 })
    put("someday", someday)
    put("source", source)
}
