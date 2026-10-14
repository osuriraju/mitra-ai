package tech.beyondscale.mitra.feature.quickadd

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
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.core.localNow
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.habits.habitsDueToday
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.model.TxDraft
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.money.ExpenseParser
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.repository.NotesRepository
import tech.beyondscale.mitra.domain.repository.WellnessRepository
import tech.beyondscale.mitra.domain.model.MOODS
import tech.beyondscale.mitra.domain.model.NoteDraft
import tech.beyondscale.mitra.domain.model.WellnessChange
import tech.beyondscale.mitra.domain.tasks.ParsedField
import tech.beyondscale.mitra.domain.tasks.ParsedTask
import tech.beyondscale.mitra.domain.tasks.QuickAddParser
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.feature.habits.HabitRowUi
import tech.beyondscale.mitra.feature.habits.habitRowUi
import tech.beyondscale.mitra.feature.today.checkHabit

enum class QuickType(val label: String) {
    Task("Task"),
    Expense("Expense"),
    Habit("Habit check"),
    Note("Note"),
    Mood("Mood"),
}

data class QuickAddUiState(
    val text: String = "",
    val type: QuickType = QuickType.Task,
    val title: String = "",
    val dueLabel: String? = null,
    val priLabel: String? = null,
    /** e.g. "💼 Work". */
    val projectLabel: String? = null,
    /** "₹240" — the parsed amount (Expense). */
    val amountLabel: String? = null,
    /** "🍛 Food & dining" (Expense). */
    val categoryLabel: String? = null,
    val merchant: String = "",
    /** Mood: today's logged mood (MOODS index). */
    val mood: Int? = null,
    val canSave: Boolean = true,
    val error: String? = null,
    /** Habit check: due-today habits whose name contains the typed text (case-insensitive). */
    val habitRows: List<HabitRowUi> = emptyList(),
)

/** Navigation for the shell. */
sealed interface QuickAddEvent {
    /** Open the habit form pre-named ([HabitEditorAction.Open][tech.beyondscale.mitra.feature.habits.HabitEditorAction.Open]). */
    data class NewHabit(val name: String) : QuickAddEvent
    /** A note was created: open it in the editor, writing. */
    data class OpenNote(val id: String) : QuickAddEvent
    data object OpenCheckin : QuickAddEvent
}

sealed interface QuickAddAction {
    data class Text(val v: String) : QuickAddAction
    data class Type(val t: QuickType) : QuickAddAction
    data object ClearDue : QuickAddAction
    data object ClearPri : QuickAddAction
    data object ClearProject : QuickAddAction
    data object ClearAmount : QuickAddAction
    data object ClearCategory : QuickAddAction
    data object Save : QuickAddAction
    data object Reset : QuickAddAction
    data class DeleteCreated(val id: String) : QuickAddAction
    data class UndoExpense(val id: String) : QuickAddAction
    data class CheckHabit(val id: String) : QuickAddAction
    data class UndoHabit(val id: String, val date: String, val value: Int?) : QuickAddAction
    data object NewHabit : QuickAddAction
    /** Mood: log today's mood and close. */
    data class Mood(val v: Int) : QuickAddAction
    data object OpenCheckin : QuickAddAction
}

/** Shell-scoped so the "Added" Undo still works after the sheet has closed. */
class QuickAddViewModel(
    private val tasks: TasksRepository,
    private val money: MoneyRepository,
    private val habits: HabitsRepository,
    private val notes: NotesRepository,
    private val wellness: WellnessRepository,
    private val clock: AppClock,
) : ViewModel() {
    private data class Input(val text: String = "", val type: QuickType = QuickType.Task, val ignore: Set<ParsedField> = emptySet(), val error: String? = null)

    private val input = MutableStateFlow(Input())
    private val queue = EventQueue<QuickAddAction>()
    val events: Flow<UiEvent<QuickAddAction>> = queue.events
    private val nav = Channel<QuickAddEvent>(Channel.BUFFERED)
    val navigation: Flow<QuickAddEvent> = nav.receiveAsFlow()

    val state: StateFlow<QuickAddUiState> = combine(input, tasks.projects, money.categories, combine(habits.habits, wellness.entries, ::Pair), clock.dayFlow()) { i, projects, categories, (allHabits, entries), today ->
        val p = QuickAddParser.parse(i.text, today, projects, i.ignore)
        val e = if (i.type == QuickType.Expense) ExpenseParser.parse(i.text, categories, i.ignore) else null
        QuickAddUiState(
            text = i.text,
            type = i.type,
            title = p.title,
            dueLabel = p.due?.let { fmtDay(it, today) },
            priLabel = p.pri?.label,
            projectLabel = p.project?.let { "${it.emoji} ${it.name}" },
            amountLabel = e?.amountPaise?.let(::inrPaise),
            categoryLabel = e?.category?.let { "${it.emoji} ${it.name}" },
            merchant = e?.merchant.orEmpty(),
            mood = entries[today]?.mood,
            canSave = when (i.type) {
                QuickType.Task -> true
                QuickType.Expense -> e?.amountPaise != null
                QuickType.Note -> i.text.isNotBlank()
                QuickType.Habit, QuickType.Mood -> false
            },
            error = i.error,
            habitRows = if (i.type != QuickType.Habit) emptyList() else {
                val q = i.text.trim()
                habitsDueToday(allHabits, today).filter { it.name.contains(q, ignoreCase = true) }.map { habitRowUi(it, today) }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuickAddUiState())

    fun onAction(a: QuickAddAction) {
        when (a) {
            is QuickAddAction.Text -> input.update { it.copy(text = a.v, error = null) }
            is QuickAddAction.Type -> input.update { it.copy(type = a.t, error = null) }
            QuickAddAction.ClearDue -> ignore(ParsedField.Due)
            QuickAddAction.ClearPri -> ignore(ParsedField.Pri)
            QuickAddAction.ClearProject -> ignore(ParsedField.Project)
            QuickAddAction.ClearAmount -> ignore(ParsedField.Amount)
            QuickAddAction.ClearCategory -> ignore(ParsedField.Category)
            QuickAddAction.Save -> save()
            QuickAddAction.Reset -> input.value = Input()
            is QuickAddAction.DeleteCreated -> viewModelScope.launch { tasks.delete(a.id) }
            is QuickAddAction.UndoExpense -> viewModelScope.launch { money.deleteTransaction(a.id) }
            is QuickAddAction.CheckHabit -> viewModelScope.launch {
                queue.checkHabit(habits, a.id, clock.today()) { id, date, v -> QuickAddAction.UndoHabit(id, date, v) }
            }
            is QuickAddAction.UndoHabit -> viewModelScope.launch { habits.setLog(a.id, a.date, a.value) }
            QuickAddAction.NewHabit -> {
                val name = input.value.text.trim()
                input.value = Input()
                nav.trySend(QuickAddEvent.NewHabit(name))
                queue.send(UiEvent.Close)
            }
            is QuickAddAction.Mood -> viewModelScope.launch {
                wellness.saveEntry(clock.today(), listOf(WellnessChange.Mood(a.v)))
                input.value = Input()
                queue.send(UiEvent.Snack("Mood logged ${MOODS[a.v]}"))
                queue.send(UiEvent.Haptic(HapticKind.Confirm))
                queue.send(UiEvent.Close)
            }
            QuickAddAction.OpenCheckin -> {
                input.value = Input()
                nav.trySend(QuickAddEvent.OpenCheckin)
                queue.send(UiEvent.Close)
            }
        }
    }

    private fun ignore(f: ParsedField) = input.update { it.copy(ignore = it.ignore + f) }

    private fun save() {
        val i = input.value
        if (i.type == QuickType.Habit || i.type == QuickType.Mood || saving) return
        saving = true
        viewModelScope.launch {
            try {
                when (i.type) {
                    QuickType.Expense -> createExpense(i)
                    QuickType.Note -> createNote(i)
                    else -> create(i)
                }
            } finally {
                saving = false
            }
        }
    }

    /** Set synchronously on Save so a double tap (or IME Done + Add) can't launch a second create. */
    private var saving = false

    private suspend fun create(i: Input) {
        run {
            val today = clock.today()
            val projects: List<Project> = tasks.projects.first()
            val p: ParsedTask = QuickAddParser.parse(i.text, today, projects, i.ignore)
            if (p.title.isBlank()) {
                input.update { it.copy(error = "Give the task a title") }
                queue.send(UiEvent.Haptic(HapticKind.Reject))
                return
            }
            val t = tasks.create(TaskDraft(p.title, projectId = p.project?.id, due = p.due, pri = p.pri))
            val text = "Added · ${t.title}" + (p.due?.let { " · ${fmtDay(it, today)}" } ?: "")
            input.value = Input()
            queue.send(UiEvent.Snack(text, QuickAddAction.DeleteCreated(t.id)))
            queue.send(UiEvent.Haptic(HapticKind.Confirm))
            queue.send(UiEvent.Close)
        }
    }

    /** The first line is the title, the rest (a shared link's text, say) the body. */
    private suspend fun createNote(i: Input) {
        val text = i.text.trim()
        if (text.isEmpty()) return
        val title = text.substringBefore('\n').trim()
        val n = notes.create(NoteDraft(title = title, body = text.substringAfter('\n', "").trim()))
        input.value = Input()
        queue.send(UiEvent.Snack("Note created"))
        nav.trySend(QuickAddEvent.OpenNote(n.id))
        queue.send(UiEvent.Close)
    }

    private suspend fun createExpense(i: Input) {
        val e = ExpenseParser.parse(i.text, money.categories.first(), i.ignore)
        val amount = e.amountPaise ?: return
        val account = money.accounts.first().firstOrNull { !it.archived }
        if (account == null) {
            input.update { it.copy(error = "Add an account first") }
            queue.send(UiEvent.Haptic(HapticKind.Reject))
            return
        }
        val now = clock.localNow()
        val time = now.hour.toString().padStart(2, '0') + ":" + now.minute.toString().padStart(2, '0')
        val tx = money.addTransaction(
            TxDraft(TxKind.Expense, amount, e.category?.id, account.id, null, e.merchant, null, now.date.toString(), time),
        )
        input.value = Input()
        queue.send(UiEvent.Snack("Expense saved · ${inrPaise(amount)}", QuickAddAction.UndoExpense(tx.id)))
        queue.send(UiEvent.Haptic(HapticKind.Confirm))
        queue.send(UiEvent.Close)
    }
}
