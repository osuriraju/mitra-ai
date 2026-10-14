package tech.beyondscale.mitra.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.ApplicationScope
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.fmtTime
import tech.beyondscale.mitra.core.minsToHm
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.model.TaskStatus
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.SubProgress
import tech.beyondscale.mitra.domain.tasks.children
import tech.beyondscale.mitra.domain.tasks.isOverdue
import tech.beyondscale.mitra.domain.tasks.subProgress
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.announce
import tech.beyondscale.mitra.domain.tasks.Completion
import tech.beyondscale.mitra.domain.tasks.toggleCompletion
import tech.beyondscale.mitra.domain.tasks.undoCompletion
import tech.beyondscale.mitra.feature.common.UiEvent
import kotlin.time.Instant

data class ParentUi(val id: String, val title: String, val progress: String)

data class TaskDetailUiState(
    /** False until the first database read, so "not found" never flashes. */
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val today: String = "",
    val title: String = "",
    val done: Boolean = false,
    val status: TaskStatus = TaskStatus.Todo,
    val priChip: Pair<String, ChipTone>? = null,
    val project: Project? = null,
    val projectId: String? = null,
    val ai: Boolean = false,
    val due: String? = null,
    val dueLine: String = "No date",
    val overdue: Boolean = false,
    val estimateLine: String = "Not set",
    val repeatLine: String = "Does not repeat",
    val parent: ParentUi? = null,
    val subtasks: List<TaskRowUi> = emptyList(),
    val subProgress: SubProgress = SubProgress(0, 0),
    /** The notes as typed (a local draft until the debounced save lands). */
    val notes: String = "",
    val createdLine: String = "",
)

sealed interface TaskDetailAction {
    data object ToggleDone : TaskDetailAction
    data class SetStatus(val s: TaskStatus) : TaskDetailAction
    data class Reschedule(val due: String?, val label: String) : TaskDetailAction
    data class Notes(val text: String) : TaskDetailAction
    data class AddSubtask(val title: String) : TaskDetailAction
    data class ToggleChild(val id: String) : TaskDetailAction
    data class UndoComplete(val id: String, val spawned: String?) : TaskDetailAction
    data class SetDue(val due: String?) : TaskDetailAction
    data object Duplicate : TaskDetailAction
    data object Delete : TaskDetailAction
}

class TaskDetailViewModel(
    private val id: String,
    private val tasks: TasksRepository,
    private val clock: AppClock,
    private val appScope: ApplicationScope,
) : ViewModel() {
    /* Notes: the field shows a local draft; writes run on the app scope (they survive the screen), one at a time,
       always saving the newest draft. All of this state is confined to the main thread. */
    /** What the user typed; shown instead of the stored notes until the store has caught up. */
    private val notesDraft = MutableStateFlow<String?>(null)
    /** The last text the repository accepted, so a flush on stop/clear does not write it twice. */
    private var savedNotes: String? = null
    /** The debounce delay only; cancelling it never cancels a write that has started. */
    private var notesJob: Job? = null
    private val notesLock = Mutex()
    private var writesInFlight = 0
    /** The stored notes from the latest emission of the task. */
    private var storedNotes: String? = null
    /** A finished write whose text the store has not echoed yet; the next emission drops the draft. */
    private var awaitingEcho: String? = null

    private val queue = EventQueue<TaskDetailAction>()
    val events: Flow<UiEvent<TaskDetailAction>> = queue.events

    private val deletedChannel = Channel<List<Task>>(Channel.BUFFERED)
    /** What [TaskDetailAction.Delete] removed (the task and its subtasks), for the shell's Undo. */
    val deleted: Flow<List<Task>> = deletedChannel.receiveAsFlow()

    init {
        // Raw emissions (no distinctUntilChanged): an echo of an unchanged value still counts as the store catching up.
        viewModelScope.launch {
            tasks.tasks.collect { all ->
                storedNotes = all.firstOrNull { it.id == id }?.notes
                val draft = notesDraft.value
                if (draft != null && !notesPending() && (sameNotes(storedNotes, draft) || draft == awaitingEcho)) {
                    notesDraft.compareAndSet(draft, null)
                }
                awaitingEcho = null
            }
        }
    }

    val state: StateFlow<TaskDetailUiState> = combine(tasks.tasks, tasks.projects, clock.dayFlow(), notesDraft, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState())

    fun onAction(a: TaskDetailAction) {
        when (a) {
            TaskDetailAction.ToggleDone -> launch { toggle(id, self = true) }
            is TaskDetailAction.ToggleChild -> launch { toggle(a.id, self = false) }
            is TaskDetailAction.UndoComplete -> launch { tasks.undoCompletion(a.id, a.spawned) }
            is TaskDetailAction.SetStatus -> launch { tasks.update(id, listOf(TaskChange.Status(a.s))) }
            is TaskDetailAction.Reschedule -> launch {
                tasks.update(id, listOf(TaskChange.Due(a.due)))
                queue.send(UiEvent.Snack("Rescheduled · ${a.label}"))
            }
            is TaskDetailAction.SetDue -> launch { tasks.update(id, listOf(TaskChange.Due(a.due))) }
            is TaskDetailAction.Notes -> {
                notesDraft.value = a.text
                savedNotes = null
                notesJob?.cancel()
                notesJob = viewModelScope.launch {
                    delay(NOTES_DEBOUNCE_MS)
                    writeNotes()
                }
            }
            is TaskDetailAction.AddSubtask -> {
                val title = a.title.trim()
                if (title.isEmpty()) return
                launch {
                    val t = find(id) ?: return@launch
                    val c = tasks.create(TaskDraft(title, parentId = id, projectId = t.projectId, due = t.due))
                    queue.send(UiEvent.Snack("Subtask added · ${c.title}"))
                }
            }
            TaskDetailAction.Duplicate -> launch {
                val t = find(id) ?: return@launch
                tasks.create(
                    TaskDraft(
                        title = "${t.title} (copy)", projectId = t.projectId, due = t.due, time = t.time, estMin = t.estMin, pri = t.pri,
                        notes = notesDraft.value ?: t.notes, recurring = t.recurring, parentId = t.parentId, someday = t.someday, goalId = t.goalId,
                    ),
                )
                queue.send(UiEvent.Snack("Duplicated"))
            }
            TaskDetailAction.Delete -> launch {
                notesJob?.cancel()
                notesDraft.value = null
                val removed = tasks.delete(id)
                if (removed.isNotEmpty()) deletedChannel.send(removed)
            }
        }
    }

    /** Saves a pending notes draft now (the screen is stopping or going away). */
    fun flushNow() {
        notesJob?.cancel()
        if (notesDraft.value != null) writeNotes()
    }

    // viewModelScope is already cancelled here; the write runs on the app scope.
    override fun onCleared() = flushNow()

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private suspend fun find(taskId: String): Task? = tasks.tasks.first().firstOrNull { it.id == taskId }

    /** Queues a write of whatever the draft is when its turn comes; writes never overlap or reorder. */
    private fun writeNotes() {
        writesInFlight++
        appScope.scope.launch(Dispatchers.Main.immediate) {
            var written: String? = null
            try {
                notesLock.withLock {
                    val text = notesDraft.value ?: return@withLock
                    if (text == savedNotes) return@withLock
                    tasks.update(id, listOf(TaskChange.Notes(text)))
                    savedNotes = text
                    written = text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left unsaved: the next keystroke, stop or clear retries.
            } finally {
                writesInFlight--
            }
            written?.let(::afterWrite)
        }
    }

    private fun afterWrite(text: String) {
        if (notesPending() || notesDraft.value != text) return
        if (sameNotes(storedNotes, text)) notesDraft.compareAndSet(text, null) else awaitingEcho = text
    }

    private fun notesPending() = writesInFlight > 0 || notesJob?.isActive == true

    /** The store may trim, or keep empty notes as null. */
    private fun sameNotes(stored: String?, draft: String) = (stored ?: "").trim() == draft.trim()

    private suspend fun toggle(taskId: String, self: Boolean) {
        val c = tasks.toggleCompletion(taskId)
        if (c is Completion.Reopened && self) queue.send(UiEvent.Snack("Marked as open", TaskDetailAction.SetStatus(TaskStatus.Done)))
        queue.announce(c) { TaskDetailAction.UndoComplete(it.id, it.spawnedId) }
    }

    private fun build(all: List<Task>, projects: List<Project>, today: String, draft: String?): TaskDetailUiState {
        val t = all.firstOrNull { it.id == id } ?: return TaskDetailUiState(loaded = true, notFound = true, today = today)
        val byId = projects.associateBy { it.id }
        val overdue = t.isOverdue(today)
        val parent = t.parentId?.let { pid -> all.firstOrNull { it.id == pid } }
        return TaskDetailUiState(
            loaded = true,
            today = today,
            title = t.title,
            done = t.done,
            status = t.status,
            priChip = t.pri?.let { p ->
                "${p.label} priority" to when (p) {
                    Priority.High -> ChipTone.Danger
                    Priority.Med -> ChipTone.Warning
                    Priority.Low -> ChipTone.Info
                }
            },
            project = t.projectId?.let(byId::get),
            projectId = t.projectId,
            ai = t.source == "ai",
            due = t.due,
            dueLine = t.due?.let { d -> fmtDay(d, today) + (t.time?.takeIf { it.isNotBlank() }?.let { " · " + fmtTime(it) } ?: "") } ?: "No date",
            overdue = overdue,
            estimateLine = t.estMin?.takeIf { it > 0 }?.let { m ->
                "${minsToHm(m)} · effort: ${when { m > 90 -> "high"; m > 30 -> "medium"; else -> "low" }}"
            } ?: "Not set",
            repeatLine = t.recurring ?: "Does not repeat",
            parent = parent?.let { p -> subProgress(all, p.id).let { s -> ParentUi(p.id, p.title, "${s.done}/${s.total}") } },
            subtasks = children(all, t.id).map { taskRowUi(it, all, byId, today, showProject = false) },
            subProgress = subProgress(all, t.id),
            notes = draft ?: t.notes,
            createdLine = createdLine(t, today),
        )
    }

    private fun createdLine(t: Task, today: String): String {
        val by = if (t.source == "ai") "Mitra (approved by you)" else "you"
        val completed = t.completedAt?.let { " · completed ${fmtDay(it, today)}" } ?: ""
        val day = localDay(t.createdAt) ?: return "Created by $by$completed"
        return "Created ${fmtDay(day, today)} by $by$completed"
    }

    /** The creation day in the device's zone (the web's `slice(0, 10)` would show the UTC day after midnight IST). */
    private fun localDay(createdAt: String): String? {
        if (createdAt.length < 10) return null
        return runCatching { Instant.parse(createdAt).toLocalDateTime(clock.zone).date.toString() }
            .getOrElse { createdAt.take(10).takeIf { d -> runCatching { LocalDate.parse(d) }.isSuccess } }
    }

    private companion object {
        const val NOTES_DEBOUNCE_MS = 600L
    }
}
