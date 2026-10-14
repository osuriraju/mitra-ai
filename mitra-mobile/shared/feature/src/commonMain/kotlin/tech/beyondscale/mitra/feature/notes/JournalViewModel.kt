package tech.beyondscale.mitra.feature.notes

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
import kotlinx.datetime.toLocalDateTime
import tech.beyondscale.mitra.core.AppClock

import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.fmtLong
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.model.AiActionDraft
import tech.beyondscale.mitra.domain.model.JournalDraft
import tech.beyondscale.mitra.domain.model.MOODS
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.NoteChange
import tech.beyondscale.mitra.domain.model.NoteDraft
import tech.beyondscale.mitra.domain.model.WellnessChange
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.notes.earlierJournal
import tech.beyondscale.mitra.domain.notes.excerpt
import tech.beyondscale.mitra.domain.notes.journalFor
import tech.beyondscale.mitra.domain.notes.journalTitle
import tech.beyondscale.mitra.domain.notes.journalWeek
import tech.beyondscale.mitra.domain.notes.localJournalDraft
import tech.beyondscale.mitra.domain.repository.ActivityRepository
import tech.beyondscale.mitra.domain.repository.AiRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.NotesRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.repository.WellnessRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent
import kotlin.time.Instant

data class JournalDayUi(val date: String, val dow: String, val num: Int, val selected: Boolean, val hasEntry: Boolean, val future: Boolean)

data class EarlierEntryUi(val id: String, val date: String, val label: String, val excerpt: String)

data class JournalUiState(
    val loaded: Boolean = false,
    val day: String = "",
    val isToday: Boolean = true,
    /** "Today" · "Mon 12 Oct". */
    val heading: String = "",
    /** "Wednesday, 14 October 2026". */
    val longDate: String = "",
    val week: List<JournalDayUi> = emptyList(),
    val canNextWeek: Boolean = false,
    /** The saved entry's note id (opens it in the editor); null when the day has none. */
    val entryId: String? = null,
    val entryAi: Boolean = false,
    /** The AI / offline draft awaiting Keep or Discard; paragraphs keep their `**bold**` marks. */
    val draft: JournalDraft? = null,
    val drafting: Boolean = false,
    val text: String = "",
    /** 0..4 or null. */
    val mood: Int? = null,
    val earlier: List<EarlierEntryUi> = emptyList(),
)

sealed interface JournalAction {
    data class SelectDay(val date: String) : JournalAction
    data object PrevWeek : JournalAction
    data object NextWeek : JournalAction
    data object Today : JournalAction
    data class Text(val text: String) : JournalAction
    data class Mood(val mood: Int?) : JournalAction
    data object Draft : JournalAction
    data object KeepDraft : JournalAction
    data object DiscardDraft : JournalAction
    data object Save : JournalAction
}

/**
 * Web `JournalScreen`: one entry per day (a note with `journalDate`), a week strip, an AI draft (`POST ai/journal-draft`
 * when AI is on, otherwise — or when it fails — the offline [localJournalDraft]) and an explicit Save, which also
 * records the mood as that day's wellness check-in.
 */
class JournalViewModel(
    private val notes: NotesRepository,
    private val wellness: WellnessRepository,
    private val ai: AiRepository,
    private val tasks: TasksRepository,
    private val habits: HabitsRepository,
    private val money: MoneyRepository,
    private val activity: ActivityRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val queue = EventQueue<JournalAction>()
    val events: Flow<UiEvent<JournalAction>> = queue.events

    private data class Local(
        /** Null = today. */
        val day: String? = null,
        /** What the user typed; null = the saved entry's body. */
        val text: String? = null,
        /** Picked mood; null = the entry's or the day's check-in mood. -1 = cleared. */
        val mood: Int? = null,
        val draft: JournalDraft? = null,
        val kept: Boolean = false,
        val drafting: Boolean = false,
    )
    private val local = MutableStateFlow(Local())

    val state: StateFlow<JournalUiState> = combine(notes.notes, wellness.entries, clock.dayFlow(), local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalUiState())

    fun onAction(a: JournalAction) {
        when (a) {
            is JournalAction.SelectDay -> if (a.date <= clock.today()) select(a.date)
            JournalAction.PrevWeek -> select(addDays(day(), -7))
            JournalAction.NextWeek -> addDays(day(), 7).takeIf { it <= clock.today() }?.let(::select)
            JournalAction.Today -> select(clock.today())
            is JournalAction.Text -> local.update { it.copy(text = a.text) }
            is JournalAction.Mood -> local.update { it.copy(mood = a.mood ?: -1) }
            JournalAction.Draft -> draft()
            JournalAction.KeepDraft -> local.update { l ->
                val d = l.draft ?: return@update l
                l.copy(text = (d.text + "\n\n" + state.value.text).trim(), kept = true, draft = null)
            }
            JournalAction.DiscardDraft -> local.update { it.copy(draft = null) }
            JournalAction.Save -> save()
        }
    }

    private fun day(): String = local.value.day ?: clock.today()

    private fun select(date: String) = local.update { Local(day = date) }

    private fun draft() {
        if (local.value.drafting) return
        val date = day()
        local.update { it.copy(drafting = true) }
        viewModelScope.launch {
            val d = try {
                if (ai.enabled.first()) {
                    when (val r = ai.journalDraft(date)) {
                        is Outcome.Ok -> r.value
                        is Outcome.Err -> { queue.send(UiEvent.Snack(r.error.message)); offlineDraft(date) }
                    }
                } else {
                    offlineDraft(date)
                }
            } finally {
                local.update { it.copy(drafting = false) }
            }
            if (day() != date) return@launch // moved to another day meanwhile
            local.update { it.copy(draft = d) }
            ai.logAction(
                AiActionDraft("Drafted journal", "Journal — ${fmtDay(date, date, relative = false)} (draft, not saved)", "Pending", "accent", false),
            )
        }
    }

    private suspend fun offlineDraft(date: String): JournalDraft {
        val zone = clock.zone
        val activities = activity.activity.first().count { runCatching { Instant.parse(it.at).toLocalDateTime(zone).date.toString() }.getOrNull() == date }
        return localJournalDraft(
            date, wellness.entries.first()[date], wellness.settings.first().sleepTarget,
            tasks.tasks.first(), habits.habits.first(), money.transactions.first(), activities,
        )
    }

    private fun save() {
        val s = state.value
        val l = local.value
        val body = s.text.trim()
        if (body.isEmpty()) {
            queue.send(UiEvent.Snack("Write something first"))
            return
        }
        val date = s.day
        val mood = s.mood
        viewModelScope.launch {
            val entry = notes.notes.first().let { journalFor(it, date) }
            if (entry != null) {
                notes.update(
                    entry.id,
                    buildList {
                        add(NoteChange.Body(body))
                        if (mood != null) add(NoteChange.Mood(mood))
                        if (l.kept) add(NoteChange.Ai(true))
                    },
                )
            } else {
                notes.create(NoteDraft(journalTitle(date), body, "Journal", listOf("journal"), journalDate = date, ai = l.kept, mood = mood))
            }
            if (mood != null) wellness.saveEntry(date, listOf(WellnessChange.Mood(mood)))
            local.update { it.copy(text = null, draft = null, kept = false, mood = null) }
            queue.send(UiEvent.Snack("Journal saved"))
        }
    }

    private fun build(all: List<Note>, entries: Map<String, WellnessEntry>, today: String, l: Local): JournalUiState {
        val day = l.day ?: today
        val entry = journalFor(all, day)
        val journalDays = all.mapNotNull { it.journalDate }.toSet()
        val mood = when (l.mood) {
            null -> entry?.mood ?: entries[day]?.mood
            -1 -> null
            else -> l.mood
        }
        return JournalUiState(
            loaded = true,
            day = day,
            isToday = day == today,
            heading = fmtDay(day, today),
            longDate = fmtLong(day),
            week = journalWeek(day).mapIndexed { i, d ->
                JournalDayUi(d, WEEK_DOW[i], d.takeLast(2).toInt(), d == day, d in journalDays, d > today)
            },
            canNextWeek = addDays(day, 7) <= today,
            entryId = entry?.id,
            entryAi = entry?.ai == true,
            draft = l.draft,
            drafting = l.drafting,
            text = l.text ?: entry?.body.orEmpty(),
            mood = mood,
            earlier = earlierJournal(all, day).map { n ->
                val d = n.journalDate!!
                EarlierEntryUi(n.id, d, fmtDay(d, today, relative = false) + (n.mood?.let { " · ${MOODS.getOrNull(it).orEmpty()}" } ?: ""), excerpt(n.body))
            },
        )
    }
}

private val WEEK_DOW = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
