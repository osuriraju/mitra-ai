package tech.beyondscale.mitra.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.diffDays
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.fmtShort
import tech.beyondscale.mitra.core.jsNum
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.goals.GoalStatus
import tech.beyondscale.mitra.domain.goals.StatusTone
import tech.beyondscale.mitra.domain.goals.goalProgress
import tech.beyondscale.mitra.domain.goals.goalStatus
import tech.beyondscale.mitra.domain.goals.ringTone
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent
import kotlin.math.max

enum class Decision(val label: String) { Keep("Keep"), Adjusted("Adjusted"), Pause("Pause") }

/** One goal page of the review. */
data class ReviewGoalUi(
    val id: String,
    val name: String,
    val pct: Int,
    /** "62%" — the ring's visible (and spoken) label. */
    val ringLabel: String,
    val ringTone: StatusTone,
    /** "+12 pts this month · on track" · "25% · nothing logged yet · slipping" */
    val line: String,
    val decision: Decision?,
    /** The adjust field's placeholder: the current target as typed ("100000"). */
    val targetPlaceholder: String,
    /** "₹" or "target" (web `sfx`). */
    val targetSuffix: String,
    val due: String,
    /** "Due 12 Jan 2027" */
    val dueLabel: String,
)

data class GoalReviewUiState(
    val loaded: Boolean = false,
    /** "Monthly · Wed 14 Oct · 3 goals" */
    val sub: String = "",
    val onTrack: Int = 0,
    /** Needs attention + behind. */
    val behind: Int = 0,
    val slipping: Int = 0,
    /** The goals that were active when the review opened, in server order (pausing one keeps its page). */
    val goals: List<ReviewGoalUi> = emptyList(),
    /** The goal whose Adjust row is open. */
    val adjusting: String? = null,
    val adjustTarget: String = "",
    val decided: Int = 0,
    /** Finish page: goal name → decision label ("Keep" / "Adjusted" / "Pause" / "No decision"). */
    val summary: List<Pair<String, String>> = emptyList(),
)

sealed interface GoalReviewAction {
    data class Decide(val goalId: String, val decision: Decision) : GoalReviewAction
    data class StartAdjust(val goalId: String) : GoalReviewAction
    data class AdjustTarget(val v: String) : GoalReviewAction
    data class AdjustDue(val goalId: String, val iso: String) : GoalReviewAction
    data class SaveAdjust(val goalId: String) : GoalReviewAction
    data object Finish : GoalReviewAction
}

/**
 * Web `GoalReviewScreen` (screens/goals.tsx) as a step pager: same decisions, copy and effects. Pause and the due date
 * write immediately; Finish moves every reviewed goal that is still active to its next review date.
 */
class GoalReviewViewModel(
    private val goals: GoalsRepository,
    private val habits: HabitsRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val queue = EventQueue<GoalReviewAction>()
    val events: Flow<UiEvent<GoalReviewAction>> = queue.events

    private data class Local(val decisions: Map<String, Decision> = emptyMap(), val adjusting: String? = null, val adjustTarget: String = "")

    private val local = MutableStateFlow(Local())
    private val captured = MutableStateFlow<List<String>?>(null)
    private var finished = false

    init {
        viewModelScope.launch { captured.value = goals.goals.first().filter { it.state == GoalState.Active }.map { it.id } }
    }

    val state: StateFlow<GoalReviewUiState> =
        combine(goals.goals, habits.habits, clock.dayFlow(), captured.filterNotNull(), local, ::build)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalReviewUiState())

    fun onAction(a: GoalReviewAction) {
        when (a) {
            is GoalReviewAction.Decide -> decide(a.goalId, a.decision)
            is GoalReviewAction.StartAdjust -> local.update { it.copy(adjusting = a.goalId, adjustTarget = "") }
            is GoalReviewAction.AdjustTarget -> local.update { it.copy(adjustTarget = a.v.filter { c -> c.isDigit() || c == '.' }) }
            is GoalReviewAction.AdjustDue -> viewModelScope.launch { goals.update(a.goalId, listOf(GoalChange.Due(a.iso))) }
            is GoalReviewAction.SaveAdjust -> {
                val target = local.value.adjustTarget.toDoubleOrNull()
                local.update { it.copy(adjusting = null, adjustTarget = "") }
                viewModelScope.launch {
                    if (target != null) goals.update(a.goalId, listOf(GoalChange.Target(target)))
                }
                decide(a.goalId, Decision.Adjusted)
            }
            GoalReviewAction.Finish -> finish()
        }
    }

    private fun decide(goalId: String, d: Decision) {
        local.update { it.copy(decisions = it.decisions + (goalId to d)) }
        if (d == Decision.Pause) viewModelScope.launch { goals.update(goalId, listOf(GoalChange.State(GoalState.Paused))) }
    }

    private fun finish() {
        if (finished) return
        finished = true
        viewModelScope.launch {
            val today = clock.today()
            val ids = captured.value ?: goals.goals.first().filter { it.state == GoalState.Active }.map { it.id }
            val now = goals.goals.first().associateBy { it.id }
            for (id in ids) {
                val g = now[id] ?: continue
                if (g.state == GoalState.Active) goals.update(id, listOf(GoalChange.ReviewOn(addDays(today, g.reviewEvery.days))))
            }
            val n = local.value.decisions.size
            queue.send(UiEvent.Snack("Review done · $n decision${if (n == 1) "" else "s"} recorded"))
            queue.send(UiEvent.Close)
        }
    }

    private fun build(all: List<Goal>, hs: List<Habit>, today: String, ids: List<String>, l: Local): GoalReviewUiState {
        val byId = all.associateBy { it.id }
        val reviewed = ids.mapNotNull { byId[it] }
        val statuses = reviewed.associate { it.id to goalStatus(it, hs, today) }
        val stillActive = reviewed.filter { it.state == GoalState.Active }.map { statuses.getValue(it.id) }
        val n = reviewed.size
        return GoalReviewUiState(
            loaded = true,
            sub = "${all.firstOrNull()?.reviewEvery?.wire ?: "Monthly"} · ${fmtDay(today, today, relative = false)} · $n goal${if (n == 1) "" else "s"}",
            onTrack = stillActive.count { it == GoalStatus.OnTrack },
            behind = stillActive.count { it == GoalStatus.Attention || it == GoalStatus.Behind },
            slipping = stillActive.count { it == GoalStatus.Slipping },
            goals = reviewed.map { g ->
                val st = statuses.getValue(g.id)
                val pct = goalProgress(g, hs, today)
                ReviewGoalUi(
                    id = g.id, name = g.name, pct = pct, ringLabel = "$pct%", ringTone = ringTone(st),
                    line = "${reviewDelta(g, hs, today)} · ${st.label.lowercase()}",
                    decision = l.decisions[g.id],
                    targetPlaceholder = jsNum(g.target), targetSuffix = g.unit.ifEmpty { "target" },
                    due = g.due, dueLabel = "Due ${fmtShort(g.due)} ${g.due.take(4)}",
                )
            },
            adjusting = l.adjusting,
            adjustTarget = l.adjustTarget,
            decided = l.decisions.size,
            summary = reviewed.map { it.name to (l.decisions[it.id]?.label ?: "No decision") },
        )
    }
}

/**
 * Web `delta` (GoalReviewScreen): a numeric goal logged in the last 31 days shows the points moved since the last
 * entry older than that (else the first recent one) — "+12 pts this month"; anything else shows its progress and
 * when it was last logged — "62% · last logged Today" / "25% · nothing logged yet".
 */
fun reviewDelta(g: Goal, habits: List<Habit>, today: String): String {
    val recent = g.progressLog.filter { diffDays(today, it.date) <= 31 }
    if (recent.isNotEmpty() && g.type == GoalType.Numeric) {
        val before = g.progressLog.lastOrNull { diffDays(today, it.date) > 31 }?.value ?: recent.first().value
        val pts = jsRound((g.current - before) / max(1.0, g.target) * 100)
        return "${if (pts >= 0) "+" else ""}$pts pts this month"
    }
    val last = g.progressLog.lastOrNull()
    return "${goalProgress(g, habits, today)}% · " + if (last != null) "last logged ${fmtDay(last.date, today)}" else "nothing logged yet"
}
