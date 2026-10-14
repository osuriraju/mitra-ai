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
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.minsToHm
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.TaskCounts
import tech.beyondscale.mitra.domain.tasks.TaskFilters
import tech.beyondscale.mitra.domain.tasks.applyFilters
import tech.beyondscale.mitra.domain.tasks.doneMin
import tech.beyondscale.mitra.domain.tasks.doneToday
import tech.beyondscale.mitra.domain.tasks.focusLine
import tech.beyondscale.mitra.domain.tasks.groupUpcoming
import tech.beyondscale.mitra.domain.tasks.isInbox
import tech.beyondscale.mitra.domain.tasks.isOverdue
import tech.beyondscale.mitra.domain.tasks.openToday
import tech.beyondscale.mitra.domain.tasks.plannedMin
import tech.beyondscale.mitra.domain.tasks.projectStats
import tech.beyondscale.mitra.domain.tasks.taskCounts
import tech.beyondscale.mitra.domain.tasks.todaySorted
import tech.beyondscale.mitra.domain.tasks.upcomingLabel
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent

enum class TasksTab(val label: String) { Inbox("Inbox"), Today("Today"), Upcoming("Upcoming"), Projects("Projects") }

data class UpcomingGroupUi(val key: String, val label: String, val rows: List<TaskRowUi>)
data class ProjectCardUi(val id: String, val emoji: String, val name: String, val open: Int, val pct: Int)

data class TasksUiState(
    val tab: TasksTab = TasksTab.Today,
    val today: String = "",
    val counts: TaskCounts = TaskCounts(0, 0, 0, 0),
    val inbox: List<TaskRowUi> = emptyList(),
    /** The first three projects, offered as triage chips on Inbox rows. */
    val triageProjects: List<Project> = emptyList(),
    val overdue: List<TaskRowUi> = emptyList(),
    /** Today's tasks (filters applied), open before done; the screen hides done ones when [showDone] is off. */
    val todayRows: List<TaskRowUi> = emptyList(),
    val openCount: Int = 0,
    val doneCount: Int = 0,
    val showDone: Boolean = true,
    val planned: String = "0m",
    val doneTime: String = "0m",
    val plannedFraction: Float = 0f,
    val focusLine: String = "",
    val upcoming: List<UpcomingGroupUi> = emptyList(),
    val projects: List<ProjectCardUi> = emptyList(),
    val filters: TaskFilters = TaskFilters(),
    val projectOptions: List<Project> = emptyList(),
    val refreshing: Boolean = false,
)

sealed interface TasksAction {
    data class SelectTab(val tab: TasksTab) : TasksAction
    data class Toggle(val id: String) : TasksAction
    /** Swipe left: move to tomorrow. */
    data class Reschedule(val id: String) : TasksAction
    data class UndoComplete(val id: String, val spawnedId: String?) : TasksAction
    data class SetDue(val id: String, val due: String?) : TasksAction
    data class Triage(val id: String, val change: TaskChange, val label: String) : TasksAction
    data class UndoTriage(val id: String) : TasksAction
    data object MoveOverdueToToday : TasksAction
    data class RestoreDues(val previous: Map<String, String?>) : TasksAction
    data class AddToInbox(val text: String) : TasksAction
    data class DeleteCreated(val id: String) : TasksAction
    data class SetFilters(val filters: TaskFilters) : TasksAction
    data object ToggleShowDone : TasksAction
    data object Refresh : TasksAction
    data class CreateProject(val name: String, val emoji: String) : TasksAction
}

class TasksViewModel(private val tasks: TasksRepository, private val clock: AppClock) : ViewModel() {
    private data class Local(
        val tab: TasksTab = TasksTab.Today,
        val filters: TaskFilters = TaskFilters(),
        val showDone: Boolean = true,
        val refreshing: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val queue = EventQueue<TasksAction>()
    val events: Flow<UiEvent<TasksAction>> = queue.events

    val state: StateFlow<TasksUiState> = combine(tasks.tasks, tasks.projects, clock.dayFlow(), local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    fun onAction(a: TasksAction) {
        when (a) {
            is TasksAction.SelectTab -> local.update { it.copy(tab = a.tab) }
            is TasksAction.SetFilters -> local.update { it.copy(filters = a.filters) }
            TasksAction.ToggleShowDone -> local.update { it.copy(showDone = !it.showDone) }
            is TasksAction.Toggle -> launch { toggle(a.id) }
            is TasksAction.UndoComplete -> launch {
                tasks.setDone(a.id, false)
                a.spawnedId?.let { tasks.delete(it) }
            }
            is TasksAction.Reschedule -> launch {
                val t = find(a.id) ?: return@launch
                tasks.update(t.id, listOf(TaskChange.Due(addDays(clock.today(), 1))))
                queue.send(UiEvent.Snack("Moved to tomorrow · ${t.title}", TasksAction.SetDue(t.id, t.due)))
            }
            is TasksAction.SetDue -> launch { tasks.update(a.id, listOf(TaskChange.Due(a.due))) }
            is TasksAction.Triage -> launch {
                val t = find(a.id) ?: return@launch
                tasks.update(t.id, listOf(a.change))
                queue.send(UiEvent.Snack("${t.title} → ${a.label}", TasksAction.UndoTriage(t.id)))
            }
            is TasksAction.UndoTriage -> launch {
                tasks.update(a.id, listOf(TaskChange.ProjectId(null), TaskChange.Due(null), TaskChange.Someday(false)))
            }
            TasksAction.MoveOverdueToToday -> launch { moveOverdue() }
            is TasksAction.RestoreDues -> launch {
                a.previous.forEach { (id, due) -> tasks.update(id, listOf(TaskChange.Due(due))) }
            }
            is TasksAction.AddToInbox -> {
                val title = a.text.trim()
                if (title.isEmpty()) return
                launch {
                    val t = tasks.create(TaskDraft(title))
                    queue.send(UiEvent.Snack("Added to inbox · ${t.title}", TasksAction.DeleteCreated(t.id)))
                }
            }
            is TasksAction.DeleteCreated -> launch { tasks.delete(a.id) }
            TasksAction.Refresh -> refresh()
            is TasksAction.CreateProject -> {
                if (validateProjectName(a.name) != null) return
                launch {
                    val p = tasks.createProject(a.name.trim(), a.emoji)
                    queue.send(UiEvent.Snack("Project created · ${p.name}"))
                }
            }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private suspend fun find(id: String): Task? = tasks.tasks.first().firstOrNull { it.id == id }

    private suspend fun toggle(id: String) {
        val t = find(id) ?: return
        if (t.done) {
            tasks.setDone(id, false)
            return
        }
        queue.send(UiEvent.Haptic(HapticKind.Confirm))
        val spawned = tasks.setDone(id, true)
        queue.send(UiEvent.Snack("Completed · ${t.title}", TasksAction.UndoComplete(id, spawned)))
    }

    private suspend fun moveOverdue() {
        val today = clock.today()
        val overdue = tasks.tasks.first().filter { it.isOverdue(today) }.applyFilters(local.value.filters, today)
        if (overdue.isEmpty()) return
        val previous = overdue.associate { it.id to it.due }
        overdue.forEach { tasks.update(it.id, listOf(TaskChange.Due(today))) }
        val n = overdue.size
        queue.send(UiEvent.Snack("$n overdue task${if (n > 1) "s" else ""} moved to today", TasksAction.RestoreDues(previous)))
    }

    private fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val result = tasks.refresh()
                // A superseded refresh is reconciled by the module refresher once the queue drains — not an error.
                val superseded = result is Outcome.Err && (result.error as? AppError.Server)?.code == TasksRepository.REFRESH_SUPERSEDED
                if (result is Outcome.Err && !superseded) {
                    val text = if (result.error is AppError.Offline) "You're offline — showing tasks saved on this phone" else result.error.message
                    queue.send(UiEvent.Snack(text))
                }
            } finally {
                local.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun build(all: List<Task>, projects: List<Project>, today: String, l: Local): TasksUiState {
        val byId = projects.associateBy { it.id }
        fun row(t: Task) = taskRowUi(t, all, byId, today)
        val f = l.filters
        val planned = plannedMin(all, today)
        val dm = doneMin(all, today)
        return TasksUiState(
            tab = l.tab,
            today = today,
            counts = taskCounts(all, projects, today),
            inbox = all.filter { it.isInbox() }.map(::row),
            triageProjects = projects.take(3),
            overdue = all.filter { it.isOverdue(today) }.applyFilters(f, today).map(::row),
            todayRows = todaySorted(all.applyFilters(f, today), today).map(::row),
            openCount = openToday(all, today).size,
            doneCount = doneToday(all, today).size,
            showDone = l.showDone,
            planned = minsToHm(planned),
            doneTime = minsToHm(dm),
            plannedFraction = if (planned > 0) dm.toFloat() / planned else 0f,
            focusLine = focusLine(all, today),
            upcoming = groupUpcoming(all.applyFilters(f, today), today).map { (key, ts) -> UpcomingGroupUi(key, upcomingLabel(key, today), ts.map(::row)) },
            projects = projects.map { p -> projectStats(all, p.id).let { s -> ProjectCardUi(p.id, p.emoji, p.name, s.open, s.pct) } },
            filters = f,
            projectOptions = projects,
            refreshing = l.refreshing,
        )
    }
}
