package tech.beyondscale.mitra.feature.goals

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
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.jsNum
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalDraft
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.ReviewEvery
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent

/** The goal form's fields (web `GoalForm` state). Numbers are the typed text; they are parsed on save. */
data class GoalFormState(
    val open: Boolean = false,
    val editId: String? = null,
    val name: String = "",
    val type: GoalType = GoalType.Numeric,
    val current: String = "0",
    val target: String = "100",
    /** "₹" or "". */
    val unit: String = "",
    val due: String = "",
    val term: GoalTerm = GoalTerm.Medium,
    val nextAction: String = "",
    val review: ReviewEvery = ReviewEvery.Monthly,
    /** One milestone title per line (create only). */
    val milestones: String = "",
    val error: String? = null,
    /** "Up to 50 milestones" — the server rejects more. */
    val milestonesError: String? = null,
)

data class GoalEditorUiState(
    val form: GoalFormState = GoalFormState(),
    val today: String = "",
    val heading: String = "New goal",
    val saveLabel: String = "Create goal",
    val typeHint: String = goalTypeHint(GoalType.Numeric),
    /** Current / Target fields: numeric and date-based goals only. */
    val showNumbers: Boolean = true,
    /** Number / ₹ Rupees: numeric goals only. */
    val showUnit: Boolean = true,
    /** The milestones textarea: create only. */
    val showMilestones: Boolean = true,
)

sealed interface GoalEditorAction {
    data object Open : GoalEditorAction
    data class Edit(val id: String) : GoalEditorAction
    data object Dismiss : GoalEditorAction
    data class Name(val v: String) : GoalEditorAction
    data class Type(val t: GoalType) : GoalEditorAction
    /**
     * Kept to digits and dots, like the web's `replace(/[^\d.]/g, '')`; an edit that would leave two dots or a value
     * above the server's 1e12 cap is refused (the field keeps its previous text).
     */
    data class Current(val v: String) : GoalEditorAction
    data class Target(val v: String) : GoalEditorAction
    data class Unit(val rupees: Boolean) : GoalEditorAction
    data class Due(val iso: String) : GoalEditorAction
    data class Term(val t: GoalTerm) : GoalEditorAction
    data class NextAction(val v: String) : GoalEditorAction
    data class Review(val r: ReviewEvery) : GoalEditorAction
    data class Milestones(val text: String) : GoalEditorAction
    data object Save : GoalEditorAction
}

/** Navigation the host performs after a save (the shell opens the new goal's detail). */
sealed interface GoalEditorEvent {
    data class Created(val id: String) : GoalEditorEvent
}

/** Web `GoalForm` type hint. */
fun goalTypeHint(t: GoalType): String = when (t) {
    GoalType.Numeric -> "Track a number toward a target (money, books, kg)."
    GoalType.Milestone -> "Progress = milestones reached ÷ total."
    GoalType.Habit -> "Progress = 30-day consistency of the habits linked to this goal."
    GoalType.Date -> "Progress = time elapsed toward the date; use milestones for evidence."
}

/**
 * Shell-scoped like `TaskEditorViewModel`: one instance hosts `GoalFormSheet`, so its snackbars and the
 * [GoalEditorEvent.Created] navigation outlive the sheet. The host collects [events] and [navigation].
 */
class GoalEditorViewModel(private val goals: GoalsRepository, private val clock: AppClock) : ViewModel() {
    private val form = MutableStateFlow(GoalFormState())
    private var editing: Goal? = null
    /** Bumped by Open / Edit / Dismiss, so an Edit still resolving when the sheet was closed or reopened is dropped. */
    private var session = 0

    private val queue = EventQueue<GoalEditorAction>()
    val events: Flow<UiEvent<GoalEditorAction>> = queue.events

    private val nav = Channel<GoalEditorEvent>(Channel.BUFFERED)
    val navigation: Flow<GoalEditorEvent> = nav.receiveAsFlow()

    val state: StateFlow<GoalEditorUiState> = combine(form, clock.dayFlow(), ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalEditorUiState())

    fun onAction(a: GoalEditorAction) {
        when (a) {
            GoalEditorAction.Open -> open()
            is GoalEditorAction.Edit -> {
                close()
                val s = session
                viewModelScope.launch {
                    val g = goals.goals.first().firstOrNull { it.id == a.id } ?: return@launch
                    if (s == session) edit(g)
                }
            }
            GoalEditorAction.Dismiss -> close()
            is GoalEditorAction.Name -> form.update { it.copy(name = a.v.take(NAME_MAX), error = null) }
            is GoalEditorAction.Type -> form.update { it.copy(type = a.t) }
            is GoalEditorAction.Current -> form.update { it.copy(current = decimal(a.v, it.current)) }
            is GoalEditorAction.Target -> form.update { it.copy(target = decimal(a.v, it.target)) }
            is GoalEditorAction.Unit -> form.update { it.copy(unit = if (a.rupees) "₹" else "") }
            is GoalEditorAction.Due -> form.update { it.copy(due = a.iso) }
            is GoalEditorAction.Term -> form.update { it.copy(term = a.t) }
            is GoalEditorAction.NextAction -> form.update { it.copy(nextAction = a.v.take(NEXT_ACTION_MAX)) }
            is GoalEditorAction.Review -> form.update { it.copy(review = a.r) }
            is GoalEditorAction.Milestones -> form.update {
                it.copy(milestones = a.text.split('\n').joinToString("\n") { line -> line.take(MILESTONE_TITLE_MAX) }, milestonesError = null)
            }
            GoalEditorAction.Save -> save()
        }
    }

    private fun open() {
        session++
        editing = null
        form.value = GoalFormState(open = true, due = addDays(clock.today(), DEFAULT_SPAN_DAYS), milestones = DEFAULT_MILESTONES)
    }

    private fun edit(g: Goal) {
        editing = g
        form.value = GoalFormState(
            open = true, editId = g.id, name = g.name, type = g.type, current = jsNum(g.current), target = jsNum(g.target),
            unit = g.unit, due = g.due, term = g.term, nextAction = g.nextAction, review = g.reviewEvery,
        )
    }

    private fun close() {
        session++
        editing = null
        form.value = GoalFormState()
    }

    private fun save() {
        val f = form.value
        if (!f.open) return
        val name = f.name.trim()
        if (name.isEmpty()) {
            form.update { it.copy(error = "Give the goal a name") }
            return
        }
        val titles = f.milestones.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (f.editId == null && titles.size > MILESTONES_MAX) {
            form.update { it.copy(milestonesError = "Up to $MILESTONES_MAX milestones") }
            return
        }
        // Web: `+target || 100`, `+current || 0` (empty, "0" and unparsable text all fall back).
        val target = parse(f.target).takeIf { it != 0.0 } ?: DEFAULT_TARGET
        val current = parse(f.current)
        val nextAction = f.nextAction.trim()
        val existing = editing
        close()
        viewModelScope.launch {
            if (existing != null) {
                // Milestone-type goals derive current/target on the server, so they are never sent. Any other type
                // sends them whenever the type changes (web `GoalForm.save` always sends both), so a milestone goal
                // turned numeric keeps its derived 2 / 4 on the server instead of stale stored numbers.
                val numbers = f.type != GoalType.Milestone
                val forceNumbers = numbers && f.type != existing.type
                val changes = buildList {
                    if (name != existing.name) add(GoalChange.Name(name))
                    if (f.type != existing.type) add(GoalChange.Type(f.type))
                    if (f.term != existing.term) add(GoalChange.Term(f.term))
                    if (f.due != existing.due) add(GoalChange.Due(f.due))
                    if (f.unit != existing.unit) add(GoalChange.Unit(f.unit))
                    if (nextAction != existing.nextAction) add(GoalChange.NextAction(nextAction))
                    if (f.review != existing.reviewEvery) add(GoalChange.ReviewEvery(f.review))
                    if (forceNumbers || (numbers && current != existing.current)) add(GoalChange.Current(current))
                    if (forceNumbers || (numbers && target != existing.target)) add(GoalChange.Target(target))
                }
                // Nothing changed: no write, but the same feedback as the web.
                if (changes.isNotEmpty()) goals.update(existing.id, changes)
                queue.send(UiEvent.Snack("Goal updated"))
            } else {
                val g = goals.create(
                    GoalDraft(
                        name = name, type = f.type, term = f.term, due = f.due, current = current, target = target,
                        unit = f.unit, nextAction = nextAction, reviewEvery = f.review,
                        milestoneTitles = titles,
                    ),
                )
                queue.send(UiEvent.Snack("Goal set · ${g.name}"))
                nav.trySend(GoalEditorEvent.Created(g.id))
            }
        }
    }

    private fun build(f: GoalFormState, today: String): GoalEditorUiState {
        val edit = f.editId != null
        return GoalEditorUiState(
            form = f,
            today = today,
            heading = if (edit) "Edit goal" else "New goal",
            saveLabel = if (edit) "Save" else "Create goal",
            typeHint = goalTypeHint(f.type),
            showNumbers = f.type == GoalType.Numeric || f.type == GoalType.Date,
            showUnit = f.type == GoalType.Numeric,
            showMilestones = !edit,
        )
    }

    private companion object {
        const val DEFAULT_SPAN_DAYS = 180
        const val DEFAULT_TARGET = 100.0
        const val DEFAULT_MILESTONES = "25%\n50%\n75%\nDone"

        /** Server caps (mitra-backend/src/goals/schemas.ts); exceeding them would fail the queued write with a 400. */
        const val NAME_MAX = 160
        const val NEXT_ACTION_MAX = 200
        const val MILESTONES_MAX = 50
        const val MILESTONE_TITLE_MAX = 120
        const val NUMBER_MAX = 1e12

        fun decimal(v: String, previous: String): String {
            val next = v.filter { it in '0'..'9' || it == '.' }
            return if (next.count { it == '.' } > 1 || parse(next) > NUMBER_MAX) previous else next
        }

        /** JS unary `+` on digits-and-dots text: "" → 0, "1.2.3" → NaN (treated as 0, i.e. falsy). */
        fun parse(v: String): Double = if (v.isEmpty()) 0.0 else v.toDoubleOrNull()?.takeUnless { it.isNaN() } ?: 0.0
    }
}
