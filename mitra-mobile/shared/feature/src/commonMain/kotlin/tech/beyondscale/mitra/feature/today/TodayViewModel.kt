package tech.beyondscale.mitra.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.fmtClock
import tech.beyondscale.mitra.core.fmtLong
import tech.beyondscale.mitra.core.greeting
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.localNow
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.core.minsToHm
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.doneMin
import tech.beyondscale.mitra.domain.tasks.doneToday
import tech.beyondscale.mitra.domain.tasks.isOverdue
import tech.beyondscale.mitra.domain.tasks.openToday
import tech.beyondscale.mitra.domain.tasks.plannedMin
import tech.beyondscale.mitra.domain.tasks.priorityTasks
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.feature.tasks.TaskRowUi
import tech.beyondscale.mitra.feature.tasks.taskRowUi

enum class StatTarget { Tasks }

data class StatUi(val label: String, val value: String, val sub: String, val target: StatTarget? = null)
data class TodayUiState(
    val dateLine: String,
    val greeting: String,
    val stats: List<StatUi>,
    val priorities: List<TaskRowUi> = emptyList(),
    val showPriorities: Boolean = true,
    val prioritiesFooter: String = "All 0 tasks for today",
    val prioritiesMeta: String = "0 done",
)

sealed interface TodayAction {
    data class Toggle(val id: String) : TodayAction
    data class UndoComplete(val id: String, val spawned: String?) : TodayAction
}

/** Header, stat strip and the "Now · priorities" section; later modules fill the remaining stats and sections. */
class TodayViewModel(
    auth: AuthRepository,
    private val tasks: TasksRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val queue = EventQueue<TodayAction>()
    val events: Flow<UiEvent<TodayAction>> = queue.events

    val state: StateFlow<TodayUiState> =
        combine(auth.session, minuteTicker(), tasks.tasks, tasks.projects, clock.dayFlow(), ::buildFrom)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildFrom(auth.session.value, Unit, emptyList(), emptyList(), clock.today()))

    fun onAction(a: TodayAction) {
        when (a) {
            is TodayAction.Toggle -> viewModelScope.launch { toggle(a.id) }
            is TodayAction.UndoComplete -> viewModelScope.launch {
                tasks.setDone(a.id, false)
                a.spawned?.let { tasks.delete(it) }
            }
        }
    }

    private suspend fun toggle(id: String) {
        val t = tasks.tasks.first().firstOrNull { it.id == id } ?: return
        if (t.done) {
            tasks.setDone(id, false)
            return
        }
        queue.send(UiEvent.Haptic(HapticKind.Confirm))
        val spawned = tasks.setDone(id, true)
        queue.send(UiEvent.Snack("Completed · ${t.title}", TodayAction.UndoComplete(id, spawned)))
    }

    /** Emits now and then at every wall-clock minute boundary so the header never goes stale. */
    private fun minuteTicker() = flow {
        while (true) {
            emit(Unit)
            val intoMinute = clock.now().toEpochMilliseconds().mod(60_000L)
            delay(60_000L - intoMinute)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildFrom(
        session: SessionState,
        tick: Unit,
        all: List<Task>,
        projects: List<Project>,
        today: String,
    ): TodayUiState {
        val user = (session as? SessionState.SignedIn)?.user
        val now = clock.localNow()
        val open = openToday(all, today).size
        val done = doneToday(all, today).size
        val overdue = all.count { it.isOverdue(today) }
        val byId = projects.associateBy { it.id }
        val statSub = (if (overdue > 0) "$overdue overdue · " else "") +
            "${minsToHm(plannedMin(all, today))} planned · ${minsToHm(doneMin(all, today))} done"
        return TodayUiState(
            dateLine = "${fmtLong(now.date.toString())} · ${fmtClock(now.hour, now.minute)}",
            greeting = greeting(now.hour) + (user?.firstName?.let { ", $it" } ?: ""),
            stats = listOf(
                StatUi("Tasks", "$done/${open + done}", statSub, StatTarget.Tasks),
                StatUi("Habits", "—", "Due today"),
                StatUi("Spent today", "—", "Of monthly budget"),
                StatUi("Sleep", "—", "Last night"),
            ),
            priorities = priorityTasks(all, today).map { taskRowUi(it, all, byId, today) },
            showPriorities = user?.settings?.todaySections?.contains("priorities") ?: true,
            prioritiesFooter = "All ${open + overdue + done} tasks for today",
            prioritiesMeta = "$done done" + if (overdue > 0) " · $overdue overdue" else "",
        )
    }
}
