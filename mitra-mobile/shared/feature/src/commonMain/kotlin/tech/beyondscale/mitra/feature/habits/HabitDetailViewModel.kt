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
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.MON
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.dayOfMonth
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.domain.goals.goalProgress
import tech.beyondscale.mitra.domain.habits.bestStreak
import tech.beyondscale.mitra.domain.habits.cadenceLabel
import tech.beyondscale.mitra.domain.habits.habitComplete
import tech.beyondscale.mitra.domain.habits.habitDone
import tech.beyondscale.mitra.domain.habits.habitDue
import tech.beyondscale.mitra.domain.habits.rateDays
import tech.beyondscale.mitra.domain.habits.streak
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitChange
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent

/* Habit detail — port of HabitDetailScreen in mitra-frontend/src/screens/habits.tsx. */

/** Weekly averages for a measurable habit (web "Trend" card). */
data class HabitTrendUi(
    val values: List<Double>,
    val labels: List<String>,
    val goal: Double,
    /** "minutes per session (weekly avg)" */
    val legend: String,
    /** "Target 20 minutes" */
    val targetLabel: String,
)

data class HabitGoalUi(val id: String, val name: String, val pct: Int, val evidence: String)

/** One of the last five days. [chip] ("Mark done" / "Undo") is null for today. */
data class RecentDayUi(val date: String, val label: String, val status: String, val chip: String?)

data class HabitDetailUiState(
    /** False until the first database read, so "not found" never flashes. */
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    /** The habit was just deleted and the screen is leaving; render nothing. */
    val closing: Boolean = false,
    val id: String = "",
    val name: String = "",
    /** "Habit · yes / no · daily" */
    val eyebrow: String = "",
    val archived: Boolean = false,
    val measurable: Boolean = false,
    val unit: String = "",
    val target: Int = 1,
    val ringPct: Int = 0,
    val ringTone: Tone = Tone.Danger,
    val streakValue: String = "",
    val bestValue: String = "",
    val monthValue: String = "",
    /** "Avg minutes" for measurable habits, else "Last 30 days". */
    val lastLabel: String = "",
    val lastValue: String = "",
    val doneToday: Boolean = false,
    /** 84 levels 0..4, oldest first; the last cell is today. */
    val heat: List<Int> = emptyList(),
    val heatCaption: String = "",
    val heatDescription: String = "",
    val trend: HabitTrendUi? = null,
    val goal: HabitGoalUi? = null,
    val recent: List<RecentDayUi> = emptyList(),
)

sealed interface HabitDetailAction {
    /** "Done today": logs the target (with Undo), or clears today when already done. */
    data object DoneToday : HabitDetailAction
    /** The measurable "Log" field: digits only; empty is ignored. */
    data class LogValue(val text: String) : HabitDetailAction
    /** A past day in "Recent": done → cleared, otherwise → target. Future days are ignored. */
    data class ToggleDay(val date: String) : HabitDetailAction
    data class SetLog(val date: String, val value: Int?) : HabitDetailAction
    data object ToggleArchive : HabitDetailAction
    data class SetArchived(val archived: Boolean) : HabitDetailAction
    data object Delete : HabitDetailAction
}

class HabitDetailViewModel(
    private val id: String,
    private val habits: HabitsRepository,
    private val goals: GoalsRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val queue = EventQueue<HabitDetailAction>()
    val events: Flow<UiEvent<HabitDetailAction>> = queue.events

    private val closing = MutableStateFlow(false)

    val state: StateFlow<HabitDetailUiState> =
        combine(habits.habits, goals.goals, clock.dayFlow(), closing) { all, goalList, today, leaving ->
            if (leaving) HabitDetailUiState(loaded = true, closing = true, id = id)
            else build(all, goalList, today)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitDetailUiState())

    fun onAction(a: HabitDetailAction) {
        when (a) {
            HabitDetailAction.DoneToday -> launch {
                val h = find() ?: return@launch
                val today = clock.today()
                if (habitDone(h, today)) {
                    habits.setLog(id, today, null)
                    queue.send(UiEvent.Snack("Unchecked"))
                    return@launch
                }
                val tap = habitComplete(h, today)
                when {
                    tap.streakMilestone -> queue.send(UiEvent.Haptic(HapticKind.Milestone))
                    tap.reachedTarget -> queue.send(UiEvent.Haptic(HapticKind.Confirm))
                }
                habits.setLog(id, tap.date, tap.value)
                tap.snack?.let { queue.send(UiEvent.Snack(it, if (tap.canUndo) HabitDetailAction.SetLog(tap.date, tap.undoValue) else null)) }
            }
            is HabitDetailAction.LogValue -> {
                val v = a.text.filter(Char::isDigit).takeIf { it.isNotEmpty() }?.toIntOrNull() ?: return
                launch {
                    val h = find() ?: return@launch
                    habits.setLog(id, clock.today(), v)
                    queue.send(UiEvent.Snack("Logged $v ${h.unit}".trimEnd()))
                }
            }
            is HabitDetailAction.ToggleDay -> launch {
                val h = find() ?: return@launch
                if (a.date > clock.today()) return@launch
                habits.setLog(id, a.date, if (habitDone(h, a.date)) null else h.target)
            }
            is HabitDetailAction.SetLog -> launch { habits.setLog(id, a.date, a.value) }
            HabitDetailAction.ToggleArchive -> launch {
                val h = find() ?: return@launch
                val archive = !h.archived
                habits.update(id, listOf(HabitChange.Archived(archive)))
                queue.send(
                    UiEvent.Snack(if (archive) "Habit archived · history kept" else "Habit restored", HabitDetailAction.SetArchived(!archive)),
                )
            }
            is HabitDetailAction.SetArchived -> launch { habits.update(id, listOf(HabitChange.Archived(a.archived))) }
            HabitDetailAction.Delete -> launch {
                if (find() == null) return@launch
                closing.value = true
                habits.delete(id)
                queue.send(UiEvent.Snack("Habit deleted"))
                queue.send(UiEvent.Close)
            }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private suspend fun find(): Habit? = habits.habits.first().firstOrNull { it.id == id }

    private fun build(all: List<Habit>, goalList: List<Goal>, today: String): HabitDetailUiState {
        val h = all.firstOrNull { it.id == id } ?: return HabitDetailUiState(loaded = true, notFound = true, id = id)
        val measurable = h.type != HabitType.Binary
        val r30 = rateDays(h, 30, today)
        val heat = habitHeat(h, today)
        val due84 = (0 until 84).count { habitDue(h, addDays(today, -it)) }
        val done84 = (0 until 84).count { habitDue(h, addDays(today, -it)) && habitDone(h, addDays(today, -it)) }
        val goal = h.goalId?.let { gid -> goalList.firstOrNull { it.id == gid } }
        return HabitDetailUiState(
            loaded = true,
            id = h.id,
            name = h.name,
            eyebrow = "Habit · ${h.type.label.lowercase()} · ${cadenceLabel(h)}",
            archived = h.archived,
            measurable = measurable,
            unit = h.unit,
            target = h.target,
            ringPct = r30.pct,
            ringTone = ringToneFor(r30.pct),
            streakValue = "${streak(h, today)} days",
            bestValue = "${bestStreak(h, today)} days",
            monthValue = monthDoneDue(h, today),
            lastLabel = if (measurable) "Avg ${h.unit}" else "Last 30 days",
            lastValue = if (measurable) average(h).toString() else "${r30.done}/${r30.due}",
            doneToday = habitDone(h, today),
            heat = heat,
            heatCaption = habitDetailHeatCaption(h),
            heatDescription = "Last 12 weeks: $done84 of $due84 scheduled days done",
            trend = if (measurable) trend(h, today) else null,
            goal = goal?.let { g -> HabitGoalUi(g.id, g.name, goalProgress(g, all, today), "Evidence: ${r30.done} sessions in 30 days") },
            recent = List(5) { i -> recentDay(h, addDays(today, -i), today) },
        )
    }

    private fun recentDay(h: Habit, d: String, today: String): RecentDayUi {
        val v = h.logs[d] ?: 0
        val hit = v >= h.target
        val status = when {
            hit -> if (h.type != HabitType.Binary) "$v ${h.unit} ✓" else "Done ✓"
            v > 0 -> "$v ${h.unit} · partial"
            habitDue(h, d) -> "Missed"
            else -> "Rest day"
        }
        return RecentDayUi(d, fmtDay(d, today), status, chip = if (d == today) null else if (hit) "Undo" else "Mark done")
    }
}

/** Ring tone for a 30-day rate: ≥ 70 success, ≥ 40 warning, else danger. */
fun ringToneFor(pct: Int): Tone = when {
    pct >= 70 -> Tone.Success
    pct >= 40 -> Tone.Warning
    else -> Tone.Danger
}

/** Heatmap caption in the middle: "Darker = more minutes" for measurable habits, else "Filled = done". */
fun habitDetailHeatCaption(h: Habit): String = if (h.type != HabitType.Binary) "Darker = more ${h.unit}" else "Filled = done"

/** Heat level for one day: rest day with nothing logged → 0, done → 4, partial → 1..3 by share of the target. */
fun heatLevel(h: Habit, d: String): Int {
    val v = h.logs[d] ?: 0
    return when {
        !habitDue(h, d) && v == 0 -> 0
        v >= h.target -> 4
        v > 0 -> maxOf(1, jsRound(v.toDouble() / h.target * 3).toInt())
        else -> 0
    }
}

/** 84 heat levels, oldest (today − 83) first. */
fun habitHeat(h: Habit, today: String): List<Int> = List(84) { i -> heatLevel(h, addDays(today, -(83 - i))) }

/**
 * Re-orders the 84 chronological cells for a 12-column, 7-row grid (one column per week, oldest on the left), so
 * the captions "12 weeks ago" … "This week" read left to right under the grid. The web stacks the weeks as rows.
 */
fun heatByWeekColumns(cells: List<Int>): List<Int> {
    val weeks = cells.size / 7
    return List(cells.size) { i -> cells[(i % weeks) * 7 + i / weeks] }
}

/** "9 / 14": days this month at target · due days from the 1st to today. */
private fun monthDoneDue(h: Habit, today: String): String {
    val month = today.take(7)
    val done = h.logs.count { (d, v) -> d.take(7) == month && v >= h.target }
    val due = (1..dayOfMonth(today)).count { habitDue(h, "$month-${it.toString().padStart(2, '0')}") }
    return "$done / $due"
}

/** Rounded mean of the non-zero logs. */
private fun average(h: Habit): Int {
    val v = h.logs.values.filter { it != 0 }
    return if (v.isEmpty()) 0 else jsRound(v.sum().toDouble() / v.size).toInt()
}

/** 12 weekly averages of the non-zero logs (`toFixed(1)`), month labels on every 4th week, goal line at the target. */
private fun trend(h: Habit, today: String): HabitTrendUi {
    val weekly = List(12) { w ->
        val vals = List(7) { i -> h.logs[addDays(today, -((11 - w) * 7 + (6 - i)))] ?: 0 }.filter { it != 0 }
        if (vals.isEmpty()) 0.0 else jsRound(vals.sum().toDouble() / vals.size * 10) / 10.0
    }
    val labels = List(12) { i -> if (i % 4 == 0) MON[addDays(today, -(11 - i) * 7).substring(5, 7).toInt() - 1] else "" }
    return HabitTrendUi(weekly, labels, h.target.toDouble(), "${h.unit} per session (weekly avg)", "Target ${h.target} ${h.unit}")
}
