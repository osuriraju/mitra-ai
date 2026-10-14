package tech.beyondscale.mitra.feature.wellness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.diffDays
import tech.beyondscale.mitra.core.dowMon
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.fmtTime
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.MOODS
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.WellnessChange
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.NotesRepository
import tech.beyondscale.mitra.domain.repository.WellnessRepository
import tech.beyondscale.mitra.domain.wellness.hm
import tech.beyondscale.mitra.domain.wellness.sleepMin
import tech.beyondscale.mitra.domain.wellness.sleepWeek
import tech.beyondscale.mitra.domain.wellness.sleepWeekAvg
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent

/* Sleep detail — port of SleepDetailScreen in mitra-frontend/src/screens/wellness.tsx. */

private val DOW_LETTER = listOf("M", "T", "W", "T", "F", "S", "S")

/** A "Related" row; [target] says where tapping it goes (null = not tappable). */
data class SleepRelatedUi(val title: String, val sub: String, val target: SleepLink?)

sealed interface SleepLink {
    data class Habit(val id: String) : SleepLink
    data class Note(val id: String) : SleepLink
    data object Checkin : SleepLink
}

data class SleepDetailUiState(
    val loaded: Boolean = false,
    val day: String = "",
    /** "Last night · Tue 13 Oct" */
    val eyebrow: String = "",
    val canNext: Boolean = false,
    val logged: Boolean = false,
    /** "7h 15m" / "Not logged". */
    val duration: String = "",
    /** "15m under target" / "on target" / "". */
    val durationSub: String = "",
    val underTarget: Boolean = false,
    val bed: String = "—",
    val wake: String = "—",
    /** Raw "HH:MM" for the time pickers (defaults when not logged). */
    val bedRaw: String = DEFAULT_BED,
    val wakeRaw: String = DEFAULT_WAKE,
    val note: String = "",
    val week: List<Double> = emptyList(),
    val weekLabels: List<String> = emptyList(),
    /** "Hours slept · target 7h 30m · avg 7.2h" */
    val weekFoot: String = "",
    val related: List<SleepRelatedUi> = emptyList(),
)

sealed interface SleepAction {
    data object Prev : SleepAction
    data object Next : SleepAction
    data class SaveTimes(val bed: String, val wake: String) : SleepAction
    data class SaveNote(val text: String) : SleepAction
}

class SleepDetailViewModel(
    private val wellness: WellnessRepository,
    habits: HabitsRepository,
    notes: NotesRepository,
    private val clock: AppClock,
) : ViewModel() {
    /** Null = last night (follows the date across midnight). */
    private val day = MutableStateFlow<String?>(null)
    private val queue = EventQueue<Nothing>()
    val events = queue.events

    private val related = combine(habits.habits, notes.notes) { h, n -> h to n }

    val state: StateFlow<SleepDetailUiState> = combine(wellness.entries, wellness.settings, related, clock.dayFlow(), day) { all, ws, (h, n), today, d ->
        buildSleep(all, ws, h, n, today, d ?: addDays(today, -1))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepDetailUiState())

    fun onAction(a: SleepAction) {
        val today = clock.today()
        val current = day.value ?: addDays(today, -1)
        when (a) {
            SleepAction.Prev -> day.value = addDays(current, -1)
            SleepAction.Next -> if (current < today) day.value = addDays(current, 1)
            is SleepAction.SaveTimes -> viewModelScope.launch {
                wellness.saveEntry(current, listOf(WellnessChange.SleepStart(a.bed), WellnessChange.SleepEnd(a.wake)))
                queue.send(UiEvent.Haptic(HapticKind.Confirm))
                queue.send(UiEvent.Snack("Sleep updated"))
            }
            is SleepAction.SaveNote -> viewModelScope.launch {
                wellness.saveEntry(current, listOf(WellnessChange.Note(a.text.trim().take(2000).ifEmpty { null })))
                queue.send(UiEvent.Snack("Note saved"))
            }
        }
    }
}

private fun buildSleep(
    all: Map<String, WellnessEntry>, ws: WellnessSettings, habits: List<Habit>, notes: List<Note>, today: String, day: String,
): SleepDetailUiState {
    val e = all[day]
    val mins = e.sleepMin()
    val week = sleepWeek(all, day)
    val sleepHabit = habits.firstOrNull { !it.archived && "sleep" in it.name.lowercase() }
    val journal = notes.firstOrNull { it.journalDate == day }
    val related = buildList {
        if (sleepHabit != null) {
            val recent = sleepHabit.logs.count { (d, v) -> diffDays(today, d) <= 3 && v != 0 }
            add(SleepRelatedUi(sleepHabit.name, "Habit · $recent of last 3 done", SleepLink.Habit(sleepHabit.id)))
        }
        if (journal != null) add(SleepRelatedUi("Journal: ${journal.body.take(40)}…", fmtDay(day, today), SleepLink.Note(journal.id)))
        add(SleepRelatedUi("Mood ${e?.mood?.let { MOODS.getOrNull(it) } ?: "—"}", "From check-in", SleepLink.Checkin))
        e?.note?.let { add(SleepRelatedUi(it, "Sleep note", null)) }
    }
    return SleepDetailUiState(
        loaded = true,
        day = day,
        eyebrow = "${if (diffDays(today, day) == 1) "Last night" else fmtDay(day, today)} · ${fmtDay(day, today, relative = false)}",
        canNext = day < today,
        logged = mins > 0,
        duration = if (mins > 0) hm(mins) else "Not logged",
        durationSub = when {
            mins == 0 -> ""
            mins < ws.sleepTarget -> "${hm(ws.sleepTarget - mins)} under target"
            else -> "on target"
        },
        underTarget = mins in 1 until ws.sleepTarget,
        bed = fmtTime(e?.sleepStart).ifEmpty { "—" },
        wake = fmtTime(e?.sleepEnd).ifEmpty { "—" },
        bedRaw = e?.sleepStart ?: DEFAULT_BED,
        wakeRaw = e?.sleepEnd ?: DEFAULT_WAKE,
        note = e?.note ?: "",
        week = week,
        weekLabels = (0 until 7).map { DOW_LETTER[dowMon(addDays(day, -(6 - it)))] },
        weekFoot = "Hours slept · target ${hm(ws.sleepTarget)} · avg ${sleepWeekAvg(week)}h",
        related = related,
    )
}
