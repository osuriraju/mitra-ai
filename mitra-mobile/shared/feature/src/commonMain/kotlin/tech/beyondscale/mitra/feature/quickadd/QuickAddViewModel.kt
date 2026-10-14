package tech.beyondscale.mitra.feature.quickadd

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
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.ParsedField
import tech.beyondscale.mitra.domain.tasks.ParsedTask
import tech.beyondscale.mitra.domain.tasks.QuickAddParser
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent

/** [arrivesWith] is the module whose phase delivers the type; null means it works today. */
enum class QuickType(val label: String, val arrivesWith: String?) {
    Task("Task", null),
    Expense("Expense", "Money"),
    Habit("Habit check", "Habits"),
    Note("Note", "Notes"),
    Mood("Mood", "Wellness"),
}

data class QuickAddUiState(
    val text: String = "",
    val type: QuickType = QuickType.Task,
    val title: String = "",
    val dueLabel: String? = null,
    val priLabel: String? = null,
    /** e.g. "💼 Work". */
    val projectLabel: String? = null,
    val notice: String? = null,
    val canSave: Boolean = true,
    val error: String? = null,
)

sealed interface QuickAddAction {
    data class Text(val v: String) : QuickAddAction
    data class Type(val t: QuickType) : QuickAddAction
    data object ClearDue : QuickAddAction
    data object ClearPri : QuickAddAction
    data object ClearProject : QuickAddAction
    data object Save : QuickAddAction
    data object Reset : QuickAddAction
    data class DeleteCreated(val id: String) : QuickAddAction
}

/** Shell-scoped so the "Added" Undo still works after the sheet has closed. */
class QuickAddViewModel(private val tasks: TasksRepository, private val clock: AppClock) : ViewModel() {
    private data class Input(val text: String = "", val type: QuickType = QuickType.Task, val ignore: Set<ParsedField> = emptySet(), val error: String? = null)

    private val input = MutableStateFlow(Input())
    private val queue = EventQueue<QuickAddAction>()
    val events: Flow<UiEvent<QuickAddAction>> = queue.events

    val state: StateFlow<QuickAddUiState> = combine(input, tasks.projects, clock.dayFlow()) { i, projects, today ->
        val p = QuickAddParser.parse(i.text, today, projects, i.ignore)
        QuickAddUiState(
            text = i.text,
            type = i.type,
            title = p.title,
            dueLabel = p.due?.let { fmtDay(it, today) },
            priLabel = p.pri?.label,
            projectLabel = p.project?.let { "${it.emoji} ${it.name}" },
            notice = i.type.arrivesWith?.let { "${i.type.label} arrives with $it — add it on the web for now." },
            canSave = i.type == QuickType.Task,
            error = i.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuickAddUiState())

    fun onAction(a: QuickAddAction) {
        when (a) {
            is QuickAddAction.Text -> input.update { it.copy(text = a.v, error = null) }
            is QuickAddAction.Type -> input.update { it.copy(type = a.t, error = null) }
            QuickAddAction.ClearDue -> ignore(ParsedField.Due)
            QuickAddAction.ClearPri -> ignore(ParsedField.Pri)
            QuickAddAction.ClearProject -> ignore(ParsedField.Project)
            QuickAddAction.Save -> save()
            QuickAddAction.Reset -> input.value = Input()
            is QuickAddAction.DeleteCreated -> viewModelScope.launch { tasks.delete(a.id) }
        }
    }

    private fun ignore(f: ParsedField) = input.update { it.copy(ignore = it.ignore + f) }

    private fun save() {
        val i = input.value
        if (i.type != QuickType.Task || saving) return
        saving = true
        viewModelScope.launch {
            try {
                create(i)
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
}
