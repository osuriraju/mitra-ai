package tech.beyondscale.mitra.feature.habits

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
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.dowMon
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.habits.HabitTap
import tech.beyondscale.mitra.domain.habits.habitComplete
import tech.beyondscale.mitra.domain.habits.habitTap
import tech.beyondscale.mitra.domain.habits.habitsDoneToday
import tech.beyondscale.mitra.domain.habits.habitsDueToday
import tech.beyondscale.mitra.domain.habits.habitsWeekRate
import tech.beyondscale.mitra.domain.habits.longestStreak
import tech.beyondscale.mitra.domain.habits.weekDots
import tech.beyondscale.mitra.domain.habits.weekRate
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitChange
import tech.beyondscale.mitra.domain.model.TimeOfDay
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent

data class HabitGroupUi(val title: String, val rows: List<HabitRowUi>)

/** One line of the "This week" card. */
data class HabitWeekUi(val id: String, val name: String, val dots: List<Boolean>, val rate: String)

data class HabitsUiState(
    val loaded: Boolean = false,
    /** 0 Today · 1 Week. */
    val tab: Int = 0,
    /** `"1 / 3"` done / due today. */
    val doneToday: String = "",
    /** `"57%"` average this week. */
    val weekRate: String = "",
    /** Longest active streak, `"12"`. */
    val longestStreak: String = "",
    /** Morning / Anytime / Evening; empty groups are left out. */
    val groups: List<HabitGroupUi> = emptyList(),
    /** Today tab with habits but none due today. */
    val nothingToday: Boolean = false,
    /** No (non-archived) habits at all. */
    val empty: Boolean = false,
    val week: List<HabitWeekUi> = emptyList(),
    /** Monday-based index of today for [WeekDots][tech.beyondscale.mitra.designsystem.components.WeekDots]. */
    val todayIndex: Int = 0,
    val refreshing: Boolean = false,
)

sealed interface HabitsAction {
    /** 0 Today, 1 Week. */
    data class Tab(val index: Int) : HabitsAction
    /** The row's check button. */
    data class Check(val id: String) : HabitsAction
    /** Swipe right: log the full target. */
    data class Complete(val id: String) : HabitsAction
    /** Swipe left: archive with Undo (history kept). */
    data class Archive(val id: String) : HabitsAction
    /** Undo of a check: put that day's value back. */
    data class SetLog(val id: String, val date: String, val value: Int?) : HabitsAction
    data class Unarchive(val id: String) : HabitsAction
    data object Refresh : HabitsAction
}

/** Port of the web `HabitsScreen` (screens/habits.tsx). */
class HabitsViewModel(private val habits: HabitsRepository, private val clock: AppClock) : ViewModel() {
    private data class Local(val tab: Int = 0, val refreshing: Boolean = false)

    private val local = MutableStateFlow(Local())
    private val queue = EventQueue<HabitsAction>()
    val events: Flow<UiEvent<HabitsAction>> = queue.events

    val state: StateFlow<HabitsUiState> = combine(habits.habits, clock.dayFlow(), local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsUiState())

    fun onAction(a: HabitsAction) {
        when (a) {
            is HabitsAction.Tab -> local.update { it.copy(tab = a.index.coerceIn(0, 1)) }
            is HabitsAction.Check -> log(a.id) { h, today -> habitTap(h, today) }
            is HabitsAction.Complete -> log(a.id) { h, today -> habitComplete(h, today) }
            is HabitsAction.Archive -> viewModelScope.launch {
                habits.update(a.id, listOf(HabitChange.Archived(true)))
                queue.send(UiEvent.Snack("Habit archived · history kept", HabitsAction.Unarchive(a.id)))
            }
            is HabitsAction.Unarchive -> viewModelScope.launch { habits.update(a.id, listOf(HabitChange.Archived(false))) }
            is HabitsAction.SetLog -> viewModelScope.launch { habits.setLog(a.id, a.date, a.value) }
            HabitsAction.Refresh -> refresh()
        }
    }

    /** The date is read at tap time, so a tap after local midnight logs the new day even before the list recomputes. */
    private fun log(id: String, decide: (Habit, String) -> HabitTap) {
        viewModelScope.launch {
            val h = habits.habits.first().firstOrNull { it.id == id } ?: return@launch
            val tap = decide(h, clock.today())
            when {
                tap.streakMilestone -> queue.send(UiEvent.Haptic(HapticKind.Milestone))
                tap.reachedTarget -> queue.send(UiEvent.Haptic(HapticKind.Confirm))
            }
            habits.setLog(tap.habitId, tap.date, tap.value)
            val snack = tap.snack ?: return@launch
            queue.send(UiEvent.Snack(snack, if (tap.canUndo) HabitsAction.SetLog(tap.habitId, tap.date, tap.undoValue) else null))
        }
    }

    private fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val result = habits.refresh()
                val superseded = result is Outcome.Err && (result.error as? AppError.Server)?.code == HabitsRepository.REFRESH_SUPERSEDED
                if (result is Outcome.Err && !superseded) {
                    val text = if (result.error is AppError.Offline) "You're offline — showing habits saved on this phone" else result.error.message
                    queue.send(UiEvent.Snack(text))
                }
            } finally {
                local.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun build(all: List<Habit>, today: String, l: Local): HabitsUiState {
        val active = all.filter { !it.archived }
        val due = habitsDueToday(active, today)
        val shown = if (l.tab == 0) due else active
        return HabitsUiState(
            loaded = true,
            tab = l.tab,
            doneToday = "${habitsDoneToday(active, today).size} / ${due.size}",
            weekRate = "${habitsWeekRate(active, today)}%",
            longestStreak = "${longestStreak(active, today)}",
            groups = TimeOfDay.entries.mapNotNull { t ->
                val rows = shown.filter { it.timeOfDay == t }
                if (rows.isEmpty()) null else HabitGroupUi(t.label, rows.map { habitRowUi(it, today) })
            },
            nothingToday = l.tab == 0 && active.isNotEmpty() && due.isEmpty(),
            empty = active.isEmpty(),
            week = active.map { HabitWeekUi(it.id, it.name, weekDots(it, today), "${weekRate(it, today)}%") },
            todayIndex = dowMon(today),
            refreshing = l.refreshing,
        )
    }
}
