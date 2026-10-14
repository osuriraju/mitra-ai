package tech.beyondscale.mitra.feature.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.domain.model.ALL_DAYS
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitChange
import tech.beyondscale.mitra.domain.model.HabitDraft
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.THREE_A_WEEK
import tech.beyondscale.mitra.domain.model.WEEKDAYS
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.domain.model.TimeOfDay as Tod

/** Web `ICONS_PICK` (screens/habits.tsx). */
val HABIT_ICONS: List<String> = listOf(
    "activity", "droplet", "book", "sun", "moon", "x", "heart", "zap", "star", "timer", "edit", "smile", "scale", "bolt", "map",
)

/** The schedule chips; "Custom" is only ever a label (derived from the day toggles). */
val SCHEDULE_PRESETS: List<String> = listOf("Every day", "Weekdays", "3× a week")
const val SCHEDULE_CUSTOM = "Custom"

/** The habit form's fields (web `HabitForm` state); [target] is the typed text. */
data class HabitFormState(
    val open: Boolean = false,
    val editId: String? = null,
    val name: String = "",
    val icon: String = "repeat",
    val type: HabitType = HabitType.Binary,
    val target: String = "10",
    val unit: String = "minutes",
    val timeOfDay: Tod = Tod.Anytime,
    val days: List<Boolean> = ALL_DAYS,
    val reminderOn: Boolean = false,
    /** "HH:MM"; kept while the switch is off so turning it back on restores it. */
    val reminder: String = DEFAULT_REMINDER,
    val goalId: String? = null,
    val gentle: Boolean = true,
    val error: String? = null,
)

/** A "Supports goal" choice; `id == null` is "None — optional". */
data class HabitGoalOption(val id: String?, val label: String)

data class HabitEditorUiState(
    val form: HabitFormState = HabitFormState(),
    val heading: String = "New habit",
    val saveLabel: String = "Create habit",
    /** "Every day" · "Weekdays" · "3× a week" · "Custom". */
    val scheduleLabel: String = "Every day",
    /** Target + unit are hidden for a Yes / No habit. */
    val showTarget: Boolean = false,
    val goals: List<HabitGoalOption> = listOf(NO_GOAL),
)

sealed interface HabitEditorAction {
    /** New habit, optionally named (Quick Add "New habit “…”"). */
    data class Open(val name: String = "") : HabitEditorAction
    data class Edit(val id: String) : HabitEditorAction
    data object Dismiss : HabitEditorAction
    data class Name(val v: String) : HabitEditorAction
    data class Icon(val id: String) : HabitEditorAction
    data class Type(val t: HabitType) : HabitEditorAction
    /** Digits only (web `replace(/\D/g, '')`). */
    data class Target(val v: String) : HabitEditorAction
    data class Unit(val v: String) : HabitEditorAction
    data class TimeOfDay(val t: Tod) : HabitEditorAction
    /** One of [SCHEDULE_PRESETS]; anything else is ignored. */
    data class Schedule(val preset: String) : HabitEditorAction
    /** Monday-based day index 0..6. */
    data class ToggleDay(val i: Int) : HabitEditorAction
    data class ReminderOn(val on: Boolean) : HabitEditorAction
    data class ReminderTime(val hhmm: String) : HabitEditorAction
    data class Goal(val id: String?) : HabitEditorAction
    data class Gentle(val on: Boolean) : HabitEditorAction
    data object Save : HabitEditorAction
}

/** Navigation the shell performs after a save. */
sealed interface HabitEditorEvent {
    /** A habit was created: the shell shows the Habits list (web `router.push(href('habits'))`). */
    data class Created(val id: String) : HabitEditorEvent
}

/**
 * Shell-scoped like `TaskEditorViewModel`: one instance hosts [HabitFormSheet] for every opener (Habits ＋, Today,
 * Quick Add, habit detail Edit), so its snackbars outlive the sheet. Reminder times are stored and synced only —
 * no notification fires until habit nudges arrive (Phase 6).
 */
class HabitEditorViewModel(
    private val habits: HabitsRepository,
    goals: GoalsRepository,
    clock: AppClock,
) : ViewModel() {
    private val form = MutableStateFlow(HabitFormState())
    private var editing: Habit? = null

    private val queue = EventQueue<HabitEditorAction>()
    val events: Flow<UiEvent<HabitEditorAction>> = queue.events
    private val nav = Channel<HabitEditorEvent>(Channel.BUFFERED)
    val navigation: Flow<HabitEditorEvent> = nav.receiveAsFlow()

    val state: StateFlow<HabitEditorUiState> = combine(form, goals.goals, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitEditorUiState())

    fun onAction(a: HabitEditorAction) {
        when (a) {
            is HabitEditorAction.Open -> {
                editing = null
                form.value = HabitFormState(open = true, name = a.name)
            }
            is HabitEditorAction.Edit -> viewModelScope.launch {
                val h = habits.habits.first().firstOrNull { it.id == a.id } ?: return@launch
                editing = h
                form.value = HabitFormState(
                    open = true, editId = h.id, name = h.name, icon = h.icon, type = h.type, target = h.target.toString(),
                    // Web: `ex?.unit || 'minutes'`.
                    unit = h.unit.ifEmpty { "minutes" }, timeOfDay = h.timeOfDay, days = h.days,
                    reminderOn = h.reminder != null, reminder = h.reminder ?: DEFAULT_REMINDER, goalId = h.goalId, gentle = h.gentle,
                )
            }
            HabitEditorAction.Dismiss -> close()
            is HabitEditorAction.Name -> form.update { it.copy(name = a.v.take(HABIT_NAME_MAX), error = null) }
            is HabitEditorAction.Icon -> form.update { it.copy(icon = a.id) }
            is HabitEditorAction.Type -> form.update { it.copy(type = a.t, unit = defaultUnit(a.t)) }
            is HabitEditorAction.Target -> form.update { it.copy(target = a.v.filter(Char::isDigit).take(TARGET_MAX_DIGITS)) }
            is HabitEditorAction.Unit -> form.update { it.copy(unit = a.v.take(HABIT_UNIT_MAX)) }
            is HabitEditorAction.TimeOfDay -> form.update { it.copy(timeOfDay = a.t) }
            is HabitEditorAction.Schedule -> presetDays(a.preset)?.let { d -> form.update { it.copy(days = d, error = null) } }
            is HabitEditorAction.ToggleDay -> if (a.i in 0..6) {
                form.update { f -> f.copy(days = f.days.mapIndexed { j, on -> if (j == a.i) !on else on }, error = null) }
            }
            is HabitEditorAction.ReminderOn -> form.update { it.copy(reminderOn = a.on) }
            is HabitEditorAction.ReminderTime -> form.update { it.copy(reminder = a.hhmm) }
            is HabitEditorAction.Goal -> form.update { it.copy(goalId = a.id) }
            is HabitEditorAction.Gentle -> form.update { it.copy(gentle = a.on) }
            HabitEditorAction.Save -> save()
        }
    }

    private fun close() {
        editing = null
        form.value = HabitFormState()
    }

    private fun save() {
        val f = form.value
        if (!f.open) return
        val name = f.name.trim()
        val error = when {
            name.isEmpty() -> "Name the habit"
            f.days.none { it } -> "Pick at least one day"
            else -> null
        }
        if (error != null) {
            form.update { it.copy(error = error) }
            return
        }
        val binary = f.type == HabitType.Binary
        // Web: `+target || 1`, held to the server's 1..100000.
        val target = if (binary) 1 else (f.target.toIntOrNull()?.takeIf { it > 0 } ?: 1).coerceAtMost(TARGET_MAX)
        val unit = if (binary) "" else f.unit.trim()
        val reminder = if (f.reminderOn) f.reminder else null
        val existing = editing
        close()
        viewModelScope.launch {
            if (existing == null) {
                val h = habits.create(
                    HabitDraft(
                        name = name, type = f.type, target = target, unit = unit, icon = f.icon, timeOfDay = f.timeOfDay,
                        days = f.days, reminder = reminder, goalId = f.goalId, gentle = f.gentle,
                    ),
                )
                queue.send(UiEvent.Snack("Habit created · ${h.name}"))
                nav.trySend(HabitEditorEvent.Created(h.id))
                return@launch
            }
            val changes = buildList {
                if (name != existing.name) add(HabitChange.Name(name))
                if (f.type != existing.type) add(HabitChange.Type(f.type))
                if (target != existing.target) add(HabitChange.Target(target))
                if (unit != existing.unit) add(HabitChange.Unit(unit))
                if (f.icon != existing.icon) add(HabitChange.Icon(f.icon))
                if (f.timeOfDay != existing.timeOfDay) add(HabitChange.TimeOfDay(f.timeOfDay))
                if (f.days != existing.days) add(HabitChange.Days(f.days))
                if (reminder != existing.reminder) add(HabitChange.Reminder(reminder))
                if (f.goalId != existing.goalId) add(HabitChange.GoalId(f.goalId))
                if (f.gentle != existing.gentle) add(HabitChange.Gentle(f.gentle))
            }
            if (changes.isEmpty()) return@launch
            habits.update(existing.id, changes)
            queue.send(UiEvent.Snack("Habit updated"))
        }
    }

    private fun build(f: HabitFormState, goals: List<Goal>): HabitEditorUiState {
        val edit = f.editId != null
        // Active goals (web); a linked goal in another state stays listed so the picker shows the real link.
        val options = goals.filter { it.state == GoalState.Active || it.id == f.goalId }.map { HabitGoalOption(it.id, it.name) }
        return HabitEditorUiState(
            form = f,
            heading = if (edit) "Edit habit" else "New habit",
            saveLabel = if (edit) "Save" else "Create habit",
            scheduleLabel = scheduleLabelFor(f.days),
            showTarget = f.type != HabitType.Binary,
            goals = listOf(NO_GOAL) + options,
        )
    }
}

private const val DEFAULT_REMINDER = "21:30"
private const val TARGET_MAX = 100_000
private const val TARGET_MAX_DIGITS = 6

// Server limits (habits/schemas.ts): longer values are rejected and the habit vanishes on the next refresh.
private const val HABIT_NAME_MAX = 80
private const val HABIT_UNIT_MAX = 20
private val NO_GOAL = HabitGoalOption(null, "None — optional")

/** Web: duration → minutes, count → times, quantity → glasses, binary → "". */
internal fun defaultUnit(t: HabitType): String = when (t) {
    HabitType.Duration -> "minutes"
    HabitType.Count -> "times"
    HabitType.Quantity -> "glasses"
    HabitType.Binary -> ""
}

internal fun presetDays(preset: String): List<Boolean>? = when (preset) {
    "Every day" -> ALL_DAYS
    "Weekdays" -> WEEKDAYS
    "3× a week" -> THREE_A_WEEK
    else -> null
}

/** The web form's `schedLabel`. */
internal fun scheduleLabelFor(days: List<Boolean>): String = when {
    days.all { it } -> "Every day"
    days == WEEKDAYS -> "Weekdays"
    days == THREE_A_WEEK -> "3× a week"
    else -> SCHEDULE_CUSTOM
}
