package tech.beyondscale.mitra.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.projectStats
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent

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
)

sealed interface ProjectDetailAction {
    data class Toggle(val id: String) : ProjectDetailAction
    data class UndoComplete(val id: String, val spawned: String?) : ProjectDetailAction
    /** Swipe left: move to tomorrow (as on the Tasks screen). */
    data class Reschedule(val id: String) : ProjectDetailAction
    data class SetDue(val id: String, val due: String?) : ProjectDetailAction
    data class Edit(val name: String, val emoji: String) : ProjectDetailAction
    data object Delete : ProjectDetailAction
}

class ProjectDetailViewModel(private val id: String, private val tasks: TasksRepository, private val clock: AppClock) : ViewModel() {
    private val queue = EventQueue<ProjectDetailAction>()
    val events: Flow<UiEvent<ProjectDetailAction>> = queue.events

    val state: StateFlow<ProjectDetailUiState> = combine(tasks.tasks, tasks.projects, clock.dayFlow(), ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectDetailUiState())

    fun onAction(a: ProjectDetailAction) {
        when (a) {
            is ProjectDetailAction.Toggle -> launch { toggle(a.id) }
            is ProjectDetailAction.UndoComplete -> launch {
                tasks.setDone(a.id, false)
                a.spawned?.let { tasks.delete(it) }
            }
            is ProjectDetailAction.Reschedule -> launch {
                val t = find(a.id) ?: return@launch
                tasks.update(t.id, listOf(TaskChange.Due(addDays(clock.today(), 1))))
                queue.send(UiEvent.Snack("Moved to tomorrow · ${t.title}", ProjectDetailAction.SetDue(t.id, t.due)))
            }
            is ProjectDetailAction.SetDue -> launch { tasks.update(a.id, listOf(TaskChange.Due(a.due))) }
            is ProjectDetailAction.Edit -> {
                if (validateProjectName(a.name) != null) return
                launch {
                    tasks.updateProject(id, a.name.trim(), a.emoji)
                    queue.send(UiEvent.Snack("Project updated"))
                }
            }
            ProjectDetailAction.Delete -> launch {
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

    private suspend fun toggle(taskId: String) {
        val t = find(taskId) ?: return
        if (t.done) {
            tasks.setDone(taskId, false)
            return
        }
        queue.send(UiEvent.Haptic(HapticKind.Confirm))
        val spawned = tasks.setDone(taskId, true)
        queue.send(UiEvent.Snack("Completed · ${t.title}", ProjectDetailAction.UndoComplete(taskId, spawned)))
    }

    private fun build(all: List<Task>, projects: List<Project>, today: String): ProjectDetailUiState {
        val p = projects.firstOrNull { it.id == id } ?: return ProjectDetailUiState(loaded = true, notFound = true, today = today)
        val byId = projects.associateBy { it.id }
        val stats = projectStats(all, id)
        return ProjectDetailUiState(
            loaded = true,
            today = today,
            name = p.name,
            emoji = p.emoji,
            open = stats.open,
            done = stats.done,
            pct = stats.pct,
            rows = all.filter { it.projectId == id }.sortedBy { it.done }.map { taskRowUi(it, all, byId, today, showProject = false) },
        )
    }
}
