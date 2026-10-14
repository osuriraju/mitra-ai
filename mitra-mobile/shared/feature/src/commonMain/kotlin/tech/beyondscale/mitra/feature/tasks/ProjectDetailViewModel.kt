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
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.projectStats
import tech.beyondscale.mitra.domain.tasks.undoCompletion
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.feature.common.toggleTask

data class ProjectDetailUiState(
    /** False until the first database read, so "not found" never flashes. */
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val today: String = "",
    val name: String = "",
    val emoji: String = "🚀",
    val open: Int = 0,
    val done: Int = 0,
    val pct: Int = 0,
    /** The project's tasks, not-done first (web list view). */
    val rows: List<TaskRowUi> = emptyList(),
    /** The project's goal link as stored (the edit form's initial value), even when the goal is unknown. */
    val goalId: String? = null,
    val goal: GoalChip? = null,
    /** `"Due Mon 12 Apr"` for the linked goal (web `fmtDay(g.due, { relative: false })`). */
    val goalDueLabel: String? = null,
    /** Edit form "Contributes to goal": every goal, any state (web `ProjectForm`), id → name. */
    val goalOptions: List<Pair<String, String>> = emptyList(),
)

sealed interface ProjectDetailAction {
    data class Toggle(val id: String) : ProjectDetailAction
    data class UndoComplete(val id: String, val spawned: String?) : ProjectDetailAction
    /** Swipe left: move to tomorrow (as on the Tasks screen). */
    data class Reschedule(val id: String) : ProjectDetailAction
    data class SetDue(val id: String, val due: String?) : ProjectDetailAction
    /** [goalId] null unlinks the goal. */
    data class Edit(val name: String, val emoji: String, val goalId: String?) : ProjectDetailAction
    data object Delete : ProjectDetailAction
}

class ProjectDetailViewModel(
    private val id: String,
    private val tasks: TasksRepository,
    private val goals: GoalsRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val queue = EventQueue<ProjectDetailAction>()
    val events: Flow<UiEvent<ProjectDetailAction>> = queue.events

    /** Set once Delete starts: the project vanishing from the flow is then expected, not "not found". */
    private val deleting = MutableStateFlow(false)
    /** Set synchronously on Edit so a double tap on Save can't launch a second update. */
    private var editing = false

    val state: StateFlow<ProjectDetailUiState> = combine(tasks.tasks, tasks.projects, goals.goals, clock.dayFlow(), deleting, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectDetailUiState())

    fun onAction(a: ProjectDetailAction) {
        when (a) {
            is ProjectDetailAction.Toggle -> launch {
                queue.toggleTask(tasks, a.id) { ProjectDetailAction.UndoComplete(it.id, it.spawnedId) }
            }
            is ProjectDetailAction.UndoComplete -> launch { tasks.undoCompletion(a.id, a.spawned) }
            is ProjectDetailAction.Reschedule -> launch {
                val t = find(a.id) ?: return@launch
                tasks.update(t.id, listOf(TaskChange.Due(addDays(clock.today(), 1))))
                queue.send(UiEvent.Snack("Moved to tomorrow · ${t.title}", ProjectDetailAction.SetDue(t.id, t.due)))
            }
            is ProjectDetailAction.SetDue -> launch { tasks.update(a.id, listOf(TaskChange.Due(a.due))) }
            is ProjectDetailAction.Edit -> {
                if (validateProjectName(a.name) != null || editing) return
                editing = true
                launch {
                    try {
                        tasks.updateProject(id, a.name.trim(), a.emoji, a.goalId)
                        queue.send(UiEvent.Snack("Project updated"))
                    } finally {
                        editing = false
                    }
                }
            }
            ProjectDetailAction.Delete -> launch {
                deleting.value = true
                tasks.deleteProject(id)
                queue.send(UiEvent.Snack("Project deleted"))
                queue.send(UiEvent.Close)
            }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private suspend fun find(taskId: String): Task? = tasks.tasks.first().firstOrNull { it.id == taskId }

    private fun build(all: List<Task>, projects: List<Project>, goals: List<Goal>, today: String, deleting: Boolean): ProjectDetailUiState {
        val p = projects.firstOrNull { it.id == id }
            ?: return ProjectDetailUiState(loaded = true, notFound = !deleting, today = today)
        val byId = projects.associateBy { it.id }
        val stats = projectStats(all, id)
        val goal = goalChip(goals, p.goalId)
        return ProjectDetailUiState(
            loaded = true,
            today = today,
            name = p.name,
            emoji = p.emoji,
            open = stats.open,
            done = stats.done,
            pct = stats.pct,
            rows = all.filter { it.projectId == id }.sortedBy { it.done }.map { taskRowUi(it, all, byId, today, showProject = false) },
            goalId = p.goalId,
            goal = goal,
            goalDueLabel = goal?.let { "Due " + fmtDay(it.due, today, relative = false) },
            goalOptions = goals.map { it.id to it.name },
        )
    }
}
