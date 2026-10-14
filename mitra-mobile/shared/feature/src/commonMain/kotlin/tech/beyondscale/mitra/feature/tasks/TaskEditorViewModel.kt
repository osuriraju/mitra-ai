package tech.beyondscale.mitra.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent

/** The task form's fields (web `TaskForm` state); empty strings are "not set", as on the web. */
data class TaskFormState(
    val open: Boolean = false,
    val editId: String? = null,
    val title: String = "",
    val projectId: String? = null,
    val due: String? = null,
    val time: String? = null,
    val pri: Priority? = null,
    val est: String = "",
    val recurring: String? = null,
    val parentId: String? = null,
    val notes: String = "",
    val error: String? = null,
)

data class ParentOption(val id: String, val title: String)

data class TaskEditorUiState(
    val form: TaskFormState = TaskFormState(),
    val projects: List<Project> = emptyList(),
    /** "Subtask of" choices: open top-level tasks, never the edited task or its own subtasks (web `TaskForm`). */
    val parentOptions: List<ParentOption> = emptyList(),
    val today: String = "",
    val heading: String = "New task",
)

sealed interface TaskEditorAction {
    data class Open(val defaults: TaskDraft) : TaskEditorAction
    data class Edit(val id: String) : TaskEditorAction
    data object Dismiss : TaskEditorAction
    data class Title(val v: String) : TaskEditorAction
    data class Project(val id: String?) : TaskEditorAction
    data class Due(val d: String?) : TaskEditorAction
    data class Time(val t: String?) : TaskEditorAction
    data class Pri(val p: Priority?) : TaskEditorAction
    /** Digits only, like the web's `replace(/\D/g, '')`, at most 4 digits. */
    data class Est(val v: String) : TaskEditorAction
    data class Recurring(val r: String?) : TaskEditorAction
    /** "Subtask of"; null makes it a top-level task. */
    data class Parent(val id: String?) : TaskEditorAction
    data class Notes(val v: String) : TaskEditorAction
    data object Save : TaskEditorAction
    /** Undo for "Task added". */
    data class DeleteCreated(val id: String) : TaskEditorAction
    /** A screen deleted tasks; offer Undo from here because this ViewModel outlives that screen. */
    data class Deleted(val removed: List<Task>) : TaskEditorAction
    data class Restore(val removed: List<Task>) : TaskEditorAction
}

/** Shell-scoped: one instance hosts the form sheet, so its Undo snackbars outlive the sheet and the task detail screen. */
class TaskEditorViewModel(private val tasks: TasksRepository, private val clock: AppClock) : ViewModel() {
    private val form = MutableStateFlow(TaskFormState())
    /** What the form was opened with: the edited task, or the create defaults (carries goalId/someday/status). */
    private var editing: Task? = null
    private var defaults: TaskDraft = TaskDraft("")

    private val queue = EventQueue<TaskEditorAction>()
    val events: Flow<UiEvent<TaskEditorAction>> = queue.events

    val state: StateFlow<TaskEditorUiState> = combine(form, tasks.tasks, tasks.projects, clock.dayFlow()) { f, all, projects, today ->
        TaskEditorUiState(f, projects, parentOptions(all, f), today, heading(f))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskEditorUiState())

    fun onAction(a: TaskEditorAction) {
        when (a) {
            is TaskEditorAction.Open -> open(a.defaults)
            is TaskEditorAction.Edit -> viewModelScope.launch {
                val t = tasks.tasks.first().firstOrNull { it.id == a.id } ?: return@launch
                edit(t)
            }
            TaskEditorAction.Dismiss -> close()
            is TaskEditorAction.Title -> form.update { it.copy(title = a.v, error = null) }
            is TaskEditorAction.Project -> form.update { it.copy(projectId = a.id) }
            is TaskEditorAction.Due -> form.update { it.copy(due = a.d) }
            is TaskEditorAction.Time -> form.update { it.copy(time = a.t) }
            is TaskEditorAction.Pri -> form.update { it.copy(pri = a.p) }
            is TaskEditorAction.Est -> form.update { it.copy(est = a.v.filter(Char::isDigit).take(EST_MAX_DIGITS)) }
            is TaskEditorAction.Recurring -> form.update { it.copy(recurring = a.r) }
            is TaskEditorAction.Parent -> form.update { it.copy(parentId = a.id) }
            is TaskEditorAction.Notes -> form.update { it.copy(notes = a.v) }
            TaskEditorAction.Save -> save()
            is TaskEditorAction.DeleteCreated -> viewModelScope.launch { tasks.delete(a.id) }
            is TaskEditorAction.Deleted -> if (a.removed.isNotEmpty()) queue.send(UiEvent.Snack("Task deleted", TaskEditorAction.Restore(a.removed)))
            is TaskEditorAction.Restore -> viewModelScope.launch { tasks.restore(a.removed) }
        }
    }

    private fun open(d: TaskDraft) {
        editing = null
        defaults = d
        form.value = TaskFormState(
            open = true, title = d.title, projectId = d.projectId, due = d.due, time = d.time, pri = d.pri,
            est = d.estMin?.toString() ?: "", recurring = d.recurring, parentId = d.parentId, notes = d.notes,
        )
    }

    private fun edit(t: Task) {
        editing = t
        form.value = TaskFormState(
            open = true, editId = t.id, title = t.title, projectId = t.projectId, due = t.due, time = t.time, pri = t.pri,
            est = t.estMin?.toString() ?: "", recurring = t.recurring, parentId = t.parentId, notes = t.notes,
        )
    }

    private fun close() {
        editing = null
        form.value = TaskFormState()
    }

    private fun save() {
        val f = form.value
        val title = f.title.trim()
        if (title.isEmpty()) {
            form.update { it.copy(error = "Give the task a title") }
            return
        }
        val est = f.est.toIntOrNull()
        val existing = editing
        close()
        viewModelScope.launch {
            if (existing != null) {
                val changes = buildList {
                    if (title != existing.title) add(TaskChange.Title(title))
                    if (f.projectId != existing.projectId) add(TaskChange.ProjectId(f.projectId))
                    if (f.due != existing.due) add(TaskChange.Due(f.due))
                    if (f.time != existing.time) add(TaskChange.Time(f.time))
                    if (f.pri != existing.pri) add(TaskChange.Pri(f.pri))
                    if (est != existing.estMin) add(TaskChange.EstMin(est))
                    if (f.recurring != existing.recurring) add(TaskChange.Recurring(f.recurring))
                    if (f.parentId != existing.parentId) add(TaskChange.Parent(f.parentId))
                    if (f.notes != existing.notes) add(TaskChange.Notes(f.notes))
                }
                if (changes.isEmpty()) return@launch
                tasks.update(existing.id, changes)
                queue.send(UiEvent.Snack("Task updated"))
            } else {
                val t = tasks.create(
                    defaults.copy(
                        title = title, projectId = f.projectId, due = f.due, time = f.time, estMin = est, pri = f.pri,
                        notes = f.notes, recurring = f.recurring, parentId = f.parentId,
                    ),
                )
                queue.send(UiEvent.Snack("Task added · ${t.title}", TaskEditorAction.DeleteCreated(t.id)))
            }
        }
    }

    private fun parentOptions(all: List<Task>, f: TaskFormState): List<ParentOption> {
        val excluded = f.editId?.let { descendantsAndSelf(all, it) } ?: emptySet()
        return all.filter { ((it.parentId == null && !it.done) || it.id == f.parentId) && it.id !in excluded }
            .map { ParentOption(it.id, it.title) }
    }

    private fun descendantsAndSelf(all: List<Task>, rootId: String): Set<String> {
        val byParent = all.groupBy { it.parentId }
        val out = mutableSetOf<String>()
        val stack = ArrayDeque(listOf(rootId))
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (out.add(next)) byParent[next]?.forEach { stack.addLast(it.id) }
        }
        return out
    }

    private fun heading(f: TaskFormState) = when {
        f.editId != null -> "Edit task"
        f.parentId != null -> "New subtask"
        else -> "New task"
    }

    private companion object {
        const val EST_MAX_DIGITS = 4
    }
}
