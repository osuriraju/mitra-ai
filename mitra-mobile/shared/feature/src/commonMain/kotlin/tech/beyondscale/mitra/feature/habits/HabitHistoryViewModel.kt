package tech.beyondscale.mitra.feature.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.MON
import tech.beyondscale.mitra.core.addMonthsKey
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.daysInMonth
import tech.beyondscale.mitra.core.fmtShort
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.monthTitle
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.designsystem.components.CalendarCell
import tech.beyondscale.mitra.designsystem.components.CalendarDayStyle
import tech.beyondscale.mitra.designsystem.components.calendarCells
import tech.beyondscale.mitra.domain.habits.bestStreak
import tech.beyondscale.mitra.domain.habits.habitDone
import tech.beyondscale.mitra.domain.habits.habitDue
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.repository.HabitsRepository

/* Habit history — port of HabitHistoryScreen (editable calendar) in mitra-frontend/src/screens/habits.tsx. */

/** One row of the month's log: "21 Sep" · "20 minutes" or "—" · Done / Partial / Missed / Rest day. */
data class HistoryLogUi(val date: String, val value: String, val status: String)

data class HabitHistoryUiState(
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    /** "History · Read" */
    val title: String = "",
    /** "2026-10" */
    val month: String = "",
    /** "October 2026" */
    val monthTitle: String = "",
    val cells: List<CalendarCell> = emptyList(),
    /** Style of every in-month day, by date. */
    val styles: Map<String, CalendarDayStyle> = emptyMap(),
    /** "80%" of this month's due days up to today. */
    val completion: String = "",
    /** "Oct" */
    val completionMonth: String = "",
    /** "Sep · 92%" or "—". */
    val bestMonth: String = "—",
    /** "41 days" ("sessions" when measurable). */
    val total: String = "",
    /** "Total time" for duration habits, else "Best streak". */
    val lastLabel: String = "",
    /** "5h 20m" or "12 days". */
    val lastValue: String = "",
    /** Up to 10 days of the month up to today, newest first. */
    val log: List<HistoryLogUi> = emptyList(),
)

sealed interface HabitHistoryAction {
    data object Prev : HabitHistoryAction
    data object Next : HabitHistoryAction
    /** A calendar day: past or today toggles done ↔ cleared; future days are ignored. */
    data class Tap(val date: String) : HabitHistoryAction
}

class HabitHistoryViewModel(
    private val id: String,
    private val habits: HabitsRepository,
    private val clock: AppClock,
) : ViewModel() {
    /** The month shown ("YYYY-MM"); null = the current month (follows today across midnight). */
    private val month = MutableStateFlow<String?>(null)

    val state: StateFlow<HabitHistoryUiState> = combine(habits.habits, clock.dayFlow(), month) { all, today, m ->
        val h = all.firstOrNull { it.id == id } ?: return@combine HabitHistoryUiState(loaded = true, notFound = true)
        buildHistory(h, today, m ?: today.take(7))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitHistoryUiState())

    fun onAction(a: HabitHistoryAction) {
        when (a) {
            HabitHistoryAction.Prev -> shift(-1)
            HabitHistoryAction.Next -> shift(1)
            is HabitHistoryAction.Tap -> viewModelScope.launch {
                if (a.date > clock.today()) return@launch
                val h = habits.habits.first().firstOrNull { it.id == id } ?: return@launch
                habits.setLog(id, a.date, if (habitDone(h, a.date)) null else h.target)
            }
        }
    }

    private fun shift(dir: Int) {
        month.value = addMonthsKey(month.value ?: clock.today().take(7), dir)
    }
}

private fun dayKey(month: String, day: Int) = "$month-${day.toString().padStart(2, '0')}"

/** Due days of [month] up to [today]. */
private fun dueDaysTo(h: Habit, month: String, today: String): Int =
    (1..daysInMonth("$month-01")).count { val d = dayKey(month, it); d <= today && habitDue(h, d) }

private fun pct(done: Int, due: Int): Int = if (due > 0) jsRound(done.toDouble() / due * 100).toInt() else 0

/** The month with the best completion among months with any log; ties go to the earliest. "—" without logs. */
fun bestMonthLabel(h: Habit, today: String): String {
    val best = h.logs.keys.map { it.take(7) }.distinct().sorted()
        .map { k -> k to pct(h.logs.count { (d, v) -> d.take(7) == k && v >= h.target }, dueDaysTo(h, k, today)) }
        .maxByOrNull { it.second } // first of the maxima
        ?: return "—"
    return "${MON[best.first.substring(5, 7).toInt() - 1]} · ${best.second}%"
}

private fun buildHistory(h: Habit, today: String, month: String): HabitHistoryUiState {
    val days = List(daysInMonth("$month-01")) { dayKey(month, it + 1) }
    val styles = days.associateWith { d ->
        val v = h.logs[d] ?: 0
        val hit = v >= h.target
        val future = d > today
        val due = habitDue(h, d)
        CalendarDayStyle(hit = hit, miss = !hit && !future && due && v == 0, today = d == today, future = future, partial = v > 0 && !hit, rest = !due)
    }
    val doneDays = days.count { (h.logs[it] ?: 0) >= h.target }
    val measurable = h.type != HabitType.Binary
    val totalTime = if (h.type == HabitType.Duration) h.logs.values.sum() else 0
    return HabitHistoryUiState(
        loaded = true,
        title = "History · ${h.name}",
        month = month,
        monthTitle = monthTitle(month),
        cells = calendarCells("$month-01"),
        styles = styles,
        completion = "${pct(doneDays, dueDaysTo(h, month, today))}%",
        completionMonth = MON[month.substring(5, 7).toInt() - 1],
        bestMonth = bestMonthLabel(h, today),
        total = "${h.logs.values.count { it >= h.target }} ${if (measurable) "sessions" else "days"}",
        lastLabel = if (h.type == HabitType.Duration) "Total time" else "Best streak",
        lastValue = if (h.type == HabitType.Duration) "${totalTime / 60}h ${totalTime % 60}m" else "${bestStreak(h, today)} days",
        log = days.filter { it <= today }.reversed().take(10).map { d ->
            val v = h.logs[d] ?: 0
            HistoryLogUi(
                date = fmtShort(d),
                value = if (v != 0) "$v${if (h.unit.isNotEmpty()) " ${h.unit}" else ""}" else "—",
                status = when {
                    v >= h.target -> "Done"
                    v > 0 -> "Partial"
                    habitDue(h, d) -> "Missed"
                    else -> "Rest day"
                },
            )
        },
    )
}
