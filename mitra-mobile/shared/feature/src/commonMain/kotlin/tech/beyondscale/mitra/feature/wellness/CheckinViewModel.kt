package tech.beyondscale.mitra.feature.wellness

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
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.jsNum
import tech.beyondscale.mitra.core.localeIN
import tech.beyondscale.mitra.core.toFixed
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.model.WellnessChange
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.model.WellnessSettingsChange
import tech.beyondscale.mitra.domain.repository.WellnessRepository
import tech.beyondscale.mitra.domain.wellness.bmi
import tech.beyondscale.mitra.domain.wellness.bmiBand
import tech.beyondscale.mitra.domain.wellness.bmiSegment
import tech.beyondscale.mitra.domain.wellness.checkinStreak
import tech.beyondscale.mitra.domain.wellness.hm
import tech.beyondscale.mitra.domain.wellness.lastWeightBefore
import tech.beyondscale.mitra.domain.wellness.sleepMinutes
import tech.beyondscale.mitra.domain.wellness.sleepTargetHint
import tech.beyondscale.mitra.domain.wellness.weightHint
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent

/* Daily check-in — port of CheckinScreen in mitra-frontend/src/screens/wellness.tsx. */

/** What the user is editing; seeded from today's entry and followed until the first edit. */
data class CheckinForm(
    val mood: Int? = null,
    val sleepStart: String = DEFAULT_BED,
    val sleepEnd: String = DEFAULT_WAKE,
    val steps: String = "",
    val water: Int = 0,
    val weight: String = "",
    val height: String = "",
    val note: String = "",
)

data class CheckinUiState(
    val loaded: Boolean = false,
    val form: CheckinForm = CheckinForm(),
    val settings: WellnessSettings = WellnessSettings(),
    /** "Saved for today — edit any time" / "Takes about 20 seconds". */
    val heading: String = "",
    /** "Streak 3 days", null without a streak. */
    val streak: String? = null,
    val sleepMin: Int = 0,
    val sleepLabel: String = "",
    val sleepHint: String = "",
    val stepsTarget: String = "",
    val weightHint: String = "",
    val bmi: Double = 0.0,
    val bmiLabel: String = "—",
    val bmiBand: String = "—",
    val bmiSegment: Int = -1,
    /** "= 72.5 ÷ 1.70²" or "". */
    val bmiFormula: String = "",
    /** "Logged Today" / "Not logged yet". */
    val moodSub: String = "",
    val shareAI: Boolean = false,
)

sealed interface CheckinAction {
    data class Mood(val v: Int) : CheckinAction
    data class SleepStart(val v: String) : CheckinAction
    data class SleepEnd(val v: String) : CheckinAction
    data class Steps(val v: String) : CheckinAction
    data class Water(val v: Int) : CheckinAction
    data class Weight(val v: String) : CheckinAction
    data class Height(val v: String) : CheckinAction
    data class Note(val v: String) : CheckinAction
    data object Save : CheckinAction
    data object Skip : CheckinAction
}

const val DEFAULT_BED = "23:30"
const val DEFAULT_WAKE = "06:30"
const val MAX_WATER = 20

class CheckinViewModel(
    private val wellness: WellnessRepository,
    private val clock: AppClock,
) : ViewModel() {
    /** Null until the user edits; then the edited form wins over the stored entry. */
    private val edited = MutableStateFlow<CheckinForm?>(null)
    private val queue = EventQueue<Nothing>()
    /** Snacks, haptics and [UiEvent.Close] after a save (the route then opens Trends). */
    val events = queue.events

    val state: StateFlow<CheckinUiState> = combine(wellness.entries, wellness.settings, clock.dayFlow(), edited) { all, ws, today, form ->
        build(all, ws, today, form ?: formOf(all[today], ws))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckinUiState())

    fun onAction(a: CheckinAction) {
        when (a) {
            is CheckinAction.Mood -> edit { it.copy(mood = if (it.mood == a.v) null else a.v.coerceIn(0, 4)) }
            is CheckinAction.SleepStart -> edit { it.copy(sleepStart = a.v) }
            is CheckinAction.SleepEnd -> edit { it.copy(sleepEnd = a.v) }
            is CheckinAction.Steps -> edit { it.copy(steps = a.v.filter(Char::isDigit).take(6)) }
            is CheckinAction.Water -> edit { it.copy(water = a.v.coerceIn(0, MAX_WATER)) }
            is CheckinAction.Weight -> edit { it.copy(weight = decimalOnly(a.v)) }
            is CheckinAction.Height -> edit { it.copy(height = a.v.filter(Char::isDigit).take(3)) }
            is CheckinAction.Note -> edit { it.copy(note = a.v.take(NOTE_MAX)) }
            CheckinAction.Save -> save()
            CheckinAction.Skip -> queue.send(UiEvent.Snack("Skipped — no data recorded"))
        }
    }

    private fun edit(f: (CheckinForm) -> CheckinForm) {
        val base = edited.value ?: state.value.form
        edited.value = f(base)
    }

    private fun save() = viewModelScope.launch {
        val today = clock.today()
        val ws = wellness.settings.first()
        val current = wellness.entries.first()[today]
        val form = edited.value ?: formOf(current, ws)
        val steps = form.steps.toIntOrNull()
        val weight = form.weight.toDoubleOrNull()?.takeIf { it > 0 }
        if (steps != null && steps > 200_000) return@launch reject("Steps must be 200,000 or fewer")
        if (weight != null && weight !in 1.0..500.0) return@launch reject("Weight must be between 1 and 500 kg")
        val height = form.height.toIntOrNull()
        if (ws.track.bmi && height != null && height !in 50..272) return@launch reject("Height must be between 50 and 272 cm")

        val wanted = buildList {
            if (ws.track.mood) add(WellnessChange.Mood(form.mood))
            if (ws.track.sleep) { add(WellnessChange.SleepStart(form.sleepStart)); add(WellnessChange.SleepEnd(form.sleepEnd)) }
            if (ws.track.steps) add(WellnessChange.Steps(steps))
            if (ws.track.water) add(WellnessChange.Water(form.water))
            if (ws.track.bmi) add(WellnessChange.Weight(weight))
            add(WellnessChange.Note(form.note.trim().ifEmpty { null }))
        }
        // Only what differs from the stored day; a new day saves every tracked field (it stamps `savedAt`).
        val changes = if (current == null) wanted else wanted.filter { it.differsFrom(current) }
        if (changes.isNotEmpty()) wellness.saveEntry(today, changes)
        if (ws.track.bmi && height != null && height != ws.height) wellness.updateSettings(listOf(WellnessSettingsChange.Height(height)))
        edited.value = null
        queue.send(UiEvent.Haptic(HapticKind.Confirm))
        queue.send(UiEvent.Snack("Check-in saved"))
        queue.send(UiEvent.Close)
    }

    private fun reject(msg: String) {
        queue.send(UiEvent.Haptic(HapticKind.Reject))
        queue.send(UiEvent.Snack(msg))
    }

    private companion object { const val NOTE_MAX = 2000 }
}

private fun WellnessChange.differsFrom(e: WellnessEntry): Boolean = when (this) {
    is WellnessChange.Mood -> v != e.mood
    is WellnessChange.SleepStart -> v != e.sleepStart
    is WellnessChange.SleepEnd -> v != e.sleepEnd
    is WellnessChange.Steps -> v != e.steps
    is WellnessChange.Water -> (v ?: 0) != (e.water ?: 0)
    is WellnessChange.Weight -> v != e.weight
    is WellnessChange.Note -> v != e.note
}

/** Digits and at most one dot, one decimal place (72.5). */
internal fun decimalOnly(s: String): String {
    val kept = s.filter { it.isDigit() || it == '.' }
    val dot = kept.indexOf('.')
    if (dot < 0) return kept.take(3)
    return kept.take(dot).take(3) + "." + kept.drop(dot + 1).filter(Char::isDigit).take(1)
}

private fun formOf(e: WellnessEntry?, ws: WellnessSettings) = CheckinForm(
    mood = e?.mood,
    sleepStart = e?.sleepStart ?: DEFAULT_BED,
    sleepEnd = e?.sleepEnd ?: DEFAULT_WAKE,
    steps = e?.steps?.takeIf { it != 0 }?.toString() ?: "",
    water = e?.water ?: 0,
    weight = e?.weight?.let(::jsNum) ?: "",
    height = ws.height.toString(),
    note = e?.note ?: "",
)

private fun build(all: Map<String, WellnessEntry>, ws: WellnessSettings, today: String, f: CheckinForm): CheckinUiState {
    val e = all[today]
    val sleep = sleepMinutes(f.sleepStart, f.sleepEnd)
    val weight = f.weight.toDoubleOrNull()
    val height = f.height.toIntOrNull()
    val b = bmi(weight, height)
    val streak = checkinStreak(all, today)
    return CheckinUiState(
        loaded = true,
        form = f,
        settings = ws,
        heading = if (e?.savedAt != null) "Saved for today — edit any time" else "Takes about 20 seconds",
        streak = if (streak > 0) "Streak $streak day${if (streak > 1) "s" else ""}" else null,
        sleepMin = sleep,
        sleepLabel = hm(sleep),
        sleepHint = sleepTargetHint(sleep, ws.sleepTarget),
        stepsTarget = "of ${localeIN(ws.stepsTarget.toDouble())}",
        weightHint = weightHint(lastWeightBefore(all, today), weight),
        bmi = b,
        bmiLabel = if (b != 0.0) jsNum(b) else "—",
        bmiBand = bmiBand(b),
        bmiSegment = bmiSegment(b),
        bmiFormula = if (f.weight.isNotEmpty() && height != null && height > 0) "= ${f.weight} ÷ ${toFixed(height / 100.0, 2)}²" else "",
        moodSub = e?.savedAt?.let { "Logged ${fmtDay(it, today)}" } ?: "Not logged yet",
        shareAI = ws.shareAI,
    )
}
