package tech.beyondscale.mitra.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.domain.goals.reviewDue
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent

/** Empty-state copy per tab (web `GoalsScreen`); only Active offers "New goal". */
enum class GoalsEmpty(val title: String, val body: String, val showNew: Boolean) {
    Active("Set one goal — Mitra will link habits and tasks to it.", "Goals give your tasks and habits a direction.", true),
    ReviewDue("No reviews due", "Goals show up here when their review date arrives.", false),
    Completed("Nothing completed or paused yet", "Completed and paused goals keep their history here.", false),
}

data class GoalsUiState(
    /** 0 Active, 1 Review due, 2 Completed (paused + completed). */
    val tab: Int = 0,
    val active: List<GoalCardUi> = emptyList(),
    val reviewDue: List<GoalCardUi> = emptyList(),
    val completed: List<GoalCardUi> = emptyList(),
    /** "3 active · 1 review due" */
    val summary: String = "0 active · 0 review due",
    val showStartReview: Boolean = false,
    val refreshing: Boolean = false,
) {
    val cards: List<GoalCardUi> get() = when (tab) {
        1 -> reviewDue
        2 -> completed
        else -> active
    }

    /** Null while the current tab has cards. */
    val empty: GoalsEmpty? get() = if (cards.isNotEmpty()) null else GoalsEmpty.entries[tab.coerceIn(0, 2)]
}

sealed interface GoalsAction {
    data class Tab(val index: Int) : GoalsAction
    data object Refresh : GoalsAction
}

/** The goals list (web `GoalsScreen`). Recomputes at local midnight: review-due and status depend on today. */
class GoalsViewModel(
    private val goals: GoalsRepository,
    habits: HabitsRepository,
    tasks: TasksRepository,
    clock: AppClock,
) : ViewModel() {
    private data class Local(val tab: Int = 0, val refreshing: Boolean = false)

    private data class Data(val goals: List<Goal>, val habits: List<Habit>, val tasks: List<Task>, val projects: List<Project>)

    private val local = MutableStateFlow(Local())
    private val queue = EventQueue<GoalsAction>()
    val events: Flow<UiEvent<GoalsAction>> = queue.events

    private val data = combine(goals.goals, habits.habits, tasks.tasks, tasks.projects, ::Data)

    val state: StateFlow<GoalsUiState> = combine(data, clock.dayFlow(), local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsUiState())

    fun onAction(a: GoalsAction) {
        when (a) {
            is GoalsAction.Tab -> local.update { it.copy(tab = a.index.coerceIn(0, 2)) }
            GoalsAction.Refresh -> refresh()
        }
    }

    private fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val result = goals.refresh()
                // A superseded refresh is reconciled by the module refresher once the queue drains — not an error.
                val superseded = result is Outcome.Err && (result.error as? AppError.Server)?.code == GoalsRepository.REFRESH_SUPERSEDED
                if (result is Outcome.Err && !superseded) {
                    val text = if (result.error is AppError.Offline) "You're offline — showing goals saved on this phone" else result.error.message
                    queue.send(UiEvent.Snack(text))
                }
            } finally {
                local.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun build(d: Data, today: String, l: Local): GoalsUiState {
        fun card(g: Goal) = goalCardUi(g, d.habits, d.tasks, d.projects, today)
        val (active, rest) = d.goals.partition { it.state == GoalState.Active }
        val activeCards = active.map(::card)
        val due = activeCards.filterIndexed { i, _ -> reviewDue(active[i], today) }
        return GoalsUiState(
            tab = l.tab,
            active = activeCards,
            reviewDue = due,
            completed = rest.map(::card),
            summary = "${active.size} active · ${due.size} review due",
            showStartReview = due.isNotEmpty(),
            refreshing = l.refreshing,
        )
    }
}
