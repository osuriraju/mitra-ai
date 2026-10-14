package tech.beyondscale.mitra.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.model.TaskStatus
import tech.beyondscale.mitra.domain.repository.TasksRepository

class FakeTasksRepository(
    tasks: List<Task> = emptyList(),
    projects: List<Project> = emptyList(),
    private val today: String = "2026-10-13",
) : TasksRepository {
    val taskFlow = MutableStateFlow(tasks)
    val projectFlow = MutableStateFlow(projects)
    override val tasks: Flow<List<Task>> = taskFlow
    override val projects: Flow<List<Project>> = projectFlow
    val calls = mutableListOf<String>()
    var refreshResult: Outcome<Unit> = Outcome.Ok(Unit)
    /** What `setDone(…, true)` reports as the spawned next occurrence. */
    var spawnOnComplete: String? = null
    private var taskSeq = 0
    private var projectSeq = 0

    override suspend fun refresh(): Outcome<Unit> { calls += "refresh"; return refreshResult }

    override suspend fun create(draft: TaskDraft): Task {
        val t = Task(
            id = "new${++taskSeq}", title = draft.title.trim(), projectId = draft.projectId, due = draft.due, time = draft.time,
            estMin = draft.estMin, pri = draft.pri, status = draft.status, done = draft.status == TaskStatus.Done,
            notes = draft.notes, recurring = draft.recurring, parentId = draft.parentId, someday = draft.someday,
        )
        calls += "create:${t.title}"
        taskFlow.update { listOf(t) + it }
        return t
    }

    override suspend fun update(id: String, changes: List<TaskChange>) {
        calls += "update:$id:" + changes.joinToString(",")
        taskFlow.update { list -> list.map { if (it.id == id) changes.fold(it, ::applyChange) else it } }
    }

    override suspend fun setDone(id: String, done: Boolean): String? {
        calls += "done:$id:$done"
        taskFlow.update { list ->
            list.map { if (it.id == id) it.copy(done = done, status = if (done) TaskStatus.Done else TaskStatus.Todo, completedAt = if (done) today else null) else it }
        }
        return if (done) spawnOnComplete else null
    }

    override suspend fun delete(id: String): List<Task> {
        calls += "delete:$id"
        val removed = taskFlow.value.filter { it.id == id || it.parentId == id }
        taskFlow.update { list -> list.filter { it !in removed } }
        return removed
    }

    override suspend fun restore(removed: List<Task>) {
        calls += "restore:" + removed.joinToString(",") { it.id }
        taskFlow.update { removed + it }
    }

    override suspend fun createProject(name: String, emoji: String): Project {
        val p = Project("proj${++projectSeq}", name.trim(), emoji)
        calls += "createProject:${p.name}"
        projectFlow.update { it + p }
        return p
    }

    override suspend fun updateProject(id: String, name: String, emoji: String) {
        calls += "updateProject:$id:$name:$emoji"
        projectFlow.update { list -> list.map { if (it.id == id) it.copy(name = name, emoji = emoji) else it } }
    }

    override suspend fun deleteProject(id: String) {
        calls += "deleteProject:$id"
        projectFlow.update { list -> list.filter { it.id != id } }
        taskFlow.update { list -> list.map { if (it.projectId == id) it.copy(projectId = null) else it } }
    }

    private fun applyChange(t: Task, c: TaskChange): Task = when (c) {
        is TaskChange.Title -> t.copy(title = c.value)
        is TaskChange.ProjectId -> t.copy(projectId = c.value)
        is TaskChange.Due -> t.copy(due = c.value)
        is TaskChange.Time -> t.copy(time = c.value)
        is TaskChange.EstMin -> t.copy(estMin = c.value)
        is TaskChange.Pri -> t.copy(pri = c.value)
        is TaskChange.Status -> t.copy(status = c.value, done = c.value == TaskStatus.Done, completedAt = if (c.value == TaskStatus.Done) today else null)
        is TaskChange.Notes -> t.copy(notes = c.value)
        is TaskChange.Recurring -> t.copy(recurring = c.value)
        is TaskChange.Someday -> t.copy(someday = c.value)
        is TaskChange.Parent -> t.copy(parentId = c.value)
    }
}
