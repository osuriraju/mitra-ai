package tech.beyondscale.mitra.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.MON
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.fmtShort
import tech.beyondscale.mitra.core.jsNum
import tech.beyondscale.mitra.core.localeIN
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.goals.GoalStatus
import tech.beyondscale.mitra.domain.goals.StatusTone
import tech.beyondscale.mitra.domain.goals.barTone
import tech.beyondscale.mitra.domain.goals.blockers
import tech.beyondscale.mitra.domain.goals.formulaText
import tech.beyondscale.mitra.domain.goals.goalProgress
import tech.beyondscale.mitra.domain.goals.goalStatus
import tech.beyondscale.mitra.domain.goals.goalTargetLabel
import tech.beyondscale.mitra.domain.goals.goalValueLabel
import tech.beyondscale.mitra.domain.goals.monthsLeft
import tech.beyondscale.mitra.domain.goals.statusTone
import tech.beyondscale.mitra.domain.habits.rateDays
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.MilestoneChange
import tech.beyondscale.mitra.domain.model.MilestoneDraft
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.undoCompletion
import tech.beyondscale.mitra.feature.common.CompletionUndo
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.feature.common.toggleTask
import tech.beyondscale.mitra.feature.tasks.TaskRowUi
import tech.beyondscale.mitra.feature.tasks.taskRowUi
import kotlin.math.max

/** One row of the milestones list (web `.ms` rows in `GoalDetailScreen`). */
data class MilestoneRowUi(
    val id: String,
    val title: String,
    /** "Reached Mon 12 Oct" · "Target 14 Jan 2027 · ₹17,000 to go" · "No date" */
    val sub: String,
    val reached: Boolean,
    /** The first open milestone after every earlier one is reached. */
    val now: Boolean,
)

enum class LinkedKind { Project, Task, Habit }

/** One "Linked" row: a project, task or habit that carries this goal's id. */
data class LinkedUi(
    val kind: LinkedKind,
    val id: String,
    val title: String,
    /** "Project · 3 open" · "Task · Tomorrow" · "Habit · 70% last 30 days" */
    val sub: String,
    /** Project emoji. */
    val emoji: String? = null,
    /** Habit icon id (web), for `MitraIcons.byName`. */
    val icon: String? = null,
    /** A done task (struck through, success tone). */
    val done: Boolean = false,
)

/** The add / edit milestone form, prefilled (web `MilestoneForm`). */
data class MilestoneFormUi(
    /** Null when adding. */
    val id: String?,
    val title: String,
    val target: String,
    val date: String?,
    val notes: String,
    /** Numeric goals only: "Reached at value". */
    val showTarget: Boolean,
    val unit: String,
)

data class GoalDetailUiState(
    /** False until the first database read, so "not found" never flashes. */
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val today: String = "",
    val id: String = "",
    val name: String = "",
    /** "Numeric goal · Medium-term · Review monthly" */
    val eyebrow: String = "",
    val state: GoalState = GoalState.Active,
    val type: GoalType = GoalType.Numeric,
    val unit: String = "",
    /** "₹58,000" · "2" · "62%" */
    val kpiValue: String = "",
    /** "of ₹1,00,000" · "of 4 milestones" · "habit consistency · 30d" */
    val kpiSub: String = "",
    val pct: Int = 0,
    val status: String = "",
    val statusTone: StatusTone = StatusTone.Neutral,
    val onTrack: Boolean = false,
    val barTone: StatusTone = StatusTone.Accent,
    /** "58% · started 14 Oct 2026" */
    val startedLine: String = "",
    /** "Due 12 Apr 2027 · 5 months left" */
    val dueLine: String = "",
    /** The text shown after "How this is calculated:". */
    val formulaText: String = "",
    /** The editable formula field (a local draft until the debounced save lands). */
    val formulaField: String = "",
    val milestones: List<MilestoneRowUi> = emptyList(),
    val chartValues: List<Double> = emptyList(),
    val chartLabels: List<String> = emptyList(),
    val chartGoal: Double? = null,
    /** "Saved (₹)" · "Progress" */
    val chartLegend: String = "Progress",
    /** "Last logged Today · “bonus”" — null when nothing has been logged. */
    val lastLogged: String? = null,
    /** The open linked task with the earliest due date (no date last). */
    val nextTask: TaskRowUi? = null,
    /** The goal's next action, or "Define the next step". */
    val nextAction: String = "",
    val linked: List<LinkedUi> = emptyList(),
    /** Projects + every linked task + habits (the web count; the list shows at most 4 tasks). */
    val linkedCount: Int = 0,
    val blockers: String = "",
    val logOpen: Boolean = false,
    val log: LogProgressUi? = null,
    val milestoneForm: MilestoneFormUi? = null,
    val milestoneDetail: MilestoneDetailUi? = null,
)

sealed interface GoalDetailAction {
    data object OpenLog : GoalDetailAction
    data object CloseLog : GoalDetailAction
    data class LogProgress(val value: String, val note: String) : GoalDetailAction
    /** [fromSheet]: the milestone detail footer — un-reaching says "Milestone reopened" and the sheet closes. */
    data class TickMilestone(val id: String, val fromSheet: Boolean = false) : GoalDetailAction
    /** Opens the milestone detail sheet; null closes it. */
    data class OpenMilestone(val id: String?) : GoalDetailAction
    /** Opens the milestone form: null adds, an id edits. */
    data class EditMilestone(val id: String?) : GoalDetailAction
    data object CloseMilestoneForm : GoalDetailAction
    data class SaveMilestone(val id: String?, val title: String, val target: String, val date: String?, val notes: String) : GoalDetailAction
    data class MilestoneNotes(val id: String, val notes: String) : GoalDetailAction
    data class DeleteMilestone(val id: String) : GoalDetailAction
    data class Formula(val text: String) : GoalDetailAction
    data object TogglePause : GoalDetailAction
    data object Complete : GoalDetailAction
    data object Reopen : GoalDetailAction
    data object Delete : GoalDetailAction
    data class ToggleTask(val id: String) : GoalDetailAction
    data class UndoTask(val undo: CompletionUndo) : GoalDetailAction
}

/**
 * Web `GoalDetailScreen`, `LogProgress`, `MilestoneForm` and `MilestoneDetailScreen` (screens/goals.tsx). Every write
 * goes through [GoalsRepository], which applies the server's maths locally; linked task checks reuse the shared
 * completion helper. Deleting the goal sends "Goal deleted" then [UiEvent.Close] (the route goes back).
 */
class GoalDetailViewModel(
    private val id: String,
    private val goals: GoalsRepository,
    private val habits: HabitsRepository,
    private val tasks: TasksRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val queue = EventQueue<GoalDetailAction>()
    val events: Flow<UiEvent<GoalDetailAction>> = queue.events

    private data class Local(
        val logOpen: Boolean = false,
        val form: MilestoneFormUi? = null,
        val detail: String? = null,
        /** Debounced text drafts: "formula" or "ms:<id>" → what the user typed. */
        val drafts: Map<String, String> = emptyMap(),
    )

    private val local = MutableStateFlow(Local())

    /*
     * Debounced text fields (formula, milestone notes): the field shows a local draft; after 600 ms (or on stop /
     * clear) the newest draft is written. Writes run on [writes], which is never cancelled, so a flush from
     * onCleared still lands; a mutex keeps them in order. Confined to the main thread.
     */
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writeLock = Mutex()
    private val debounce = mutableMapOf<String, Job>()
    private val pending = mutableMapOf<String, suspend (String) -> Unit>()

    private data class Inputs(val goals: List<Goal>, val habits: List<Habit>, val tasks: List<Task>, val projects: List<Project>)

    val state: StateFlow<GoalDetailUiState> =
        combine(
            combine(goals.goals, habits.habits, tasks.tasks, tasks.projects, ::Inputs),
            clock.dayFlow(),
            local,
            ::build,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalDetailUiState())

    fun onAction(a: GoalDetailAction) {
        when (a) {
            GoalDetailAction.OpenLog -> local.update { it.copy(logOpen = true) }
            GoalDetailAction.CloseLog -> local.update { it.copy(logOpen = false) }
            is GoalDetailAction.LogProgress -> logProgress(a.value, a.note)
            is GoalDetailAction.TickMilestone -> tick(a.id, a.fromSheet)
            is GoalDetailAction.OpenMilestone -> local.update { it.copy(detail = a.id) }
            is GoalDetailAction.EditMilestone -> launch {
                val g = goal() ?: return@launch
                val m = a.id?.let { mid -> g.milestones.firstOrNull { it.id == mid } }
                if (a.id != null && m == null) return@launch
                local.update {
                    it.copy(
                        form = MilestoneFormUi(
                            id = m?.id, title = m?.title ?: "", target = m?.target?.takeIf { t -> t != 0.0 }?.let(::jsNum) ?: "",
                            date = m?.targetDate, notes = m?.notes ?: "", showTarget = g.type == GoalType.Numeric, unit = g.unit,
                        ),
                    )
                }
            }
            GoalDetailAction.CloseMilestoneForm -> local.update { it.copy(form = null) }
            is GoalDetailAction.SaveMilestone -> saveMilestone(a)
            is GoalDetailAction.MilestoneNotes -> edit("ms:${a.id}", a.notes) { text ->
                goals.updateMilestone(id, a.id, listOf(MilestoneChange.Notes(text)))
            }
            is GoalDetailAction.DeleteMilestone -> launch {
                drop("ms:${a.id}")
                local.update { it.copy(detail = if (it.detail == a.id) null else it.detail) }
                goals.deleteMilestone(id, a.id)
                queue.send(UiEvent.Snack("Milestone deleted"))
            }
            is GoalDetailAction.Formula -> edit(FORMULA, a.text) { text ->
                goals.update(id, listOf(GoalChange.Formula(text.ifBlank { null })))
            }
            GoalDetailAction.TogglePause -> launch {
                val g = goal() ?: return@launch
                val resume = g.state == GoalState.Paused
                goals.update(id, listOf(GoalChange.State(if (resume) GoalState.Active else GoalState.Paused)))
                queue.send(UiEvent.Snack(if (resume) "Goal resumed" else "Goal paused · history kept"))
            }
            GoalDetailAction.Complete -> launch {
                goals.update(id, listOf(GoalChange.State(GoalState.Completed)))
                queue.send(UiEvent.Snack("🎉 Goal completed"))
            }
            GoalDetailAction.Reopen -> launch { goals.update(id, listOf(GoalChange.State(GoalState.Active))) }
            GoalDetailAction.Delete -> launch {
                debounce.values.forEach { it.cancel() }
                debounce.clear()
                pending.clear()
                goals.delete(id)
                queue.send(UiEvent.Snack("Goal deleted"))
                queue.send(UiEvent.Close)
            }
            is GoalDetailAction.ToggleTask -> launch { queue.toggleTask(tasks, a.id) { GoalDetailAction.UndoTask(it) } }
            is GoalDetailAction.UndoTask -> launch { tasks.undoCompletion(a.undo.id, a.undo.spawnedId) }
        }
    }

    /** Saves pending text drafts now (the screen is stopping or going away). */
    fun flushNow() {
        pending.keys.toList().forEach { key ->
            debounce.remove(key)?.cancel()
            flush(key)
        }
    }

    // viewModelScope is already cancelled here; the writes run on [writes].
    override fun onCleared() = flushNow()

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private suspend fun goal(): Goal? = goals.goals.first().firstOrNull { it.id == id }

    private fun logProgress(value: String, note: String) = launch {
        val g = goal() ?: return@launch
        val numeric = g.type == GoalType.Numeric || g.type == GoalType.Date
        val n = value.toDoubleOrNull() ?: 0.0
        if (numeric && n <= 0) return@launch
        local.update { it.copy(logOpen = false) }
        goals.logProgress(id, if (numeric) n else g.current, note.ifEmpty { null })
        queue.send(UiEvent.Snack("Progress logged" + if (numeric) " · +${g.unit}${localeIN(n)}" else ""))
    }

    /** The date is the device's local day at tap time. */
    private fun tick(mid: String, fromSheet: Boolean) = launch {
        val m = goal()?.milestones?.firstOrNull { it.id == mid } ?: return@launch
        val reaching = m.reachedOn == null
        if (fromSheet) local.update { it.copy(detail = null) }
        goals.updateMilestone(id, mid, listOf(MilestoneChange.ReachedOn(if (reaching) clock.today() else null)))
        when {
            reaching -> queue.send(UiEvent.Snack("Milestone reached · ${m.title}"))
            fromSheet -> queue.send(UiEvent.Snack("Milestone reopened"))
        }
    }

    private fun saveMilestone(a: GoalDetailAction.SaveMilestone) {
        val title = a.title.trim()
        if (title.isEmpty()) return
        val target = a.target.toDoubleOrNull()?.takeIf { it != 0.0 }
        val date = a.date?.ifEmpty { null }
        local.update { it.copy(form = null) }
        launch {
            if (a.id == null) {
                goals.addMilestone(id, MilestoneDraft(title, target, date, a.notes.ifEmpty { null }))
                queue.send(UiEvent.Snack("Milestone added"))
                return@launch
            }
            val m = goal()?.milestones?.firstOrNull { it.id == a.id } ?: return@launch
            val changes = buildList {
                if (title != m.title) add(MilestoneChange.Title(title))
                if (target != m.target) add(MilestoneChange.Target(target))
                if (date != m.targetDate) add(MilestoneChange.TargetDate(date))
                if (a.notes != (m.notes ?: "")) add(MilestoneChange.Notes(a.notes))
            }
            if (changes.isNotEmpty()) {
                drop("ms:${a.id}")
                goals.updateMilestone(id, a.id, changes)
            }
            queue.send(UiEvent.Snack("Milestone updated"))
        }
    }

    private fun edit(key: String, text: String, write: suspend (String) -> Unit) {
        local.update { it.copy(drafts = it.drafts + (key to text)) }
        pending[key] = write
        debounce.remove(key)?.cancel()
        debounce[key] = viewModelScope.launch {
            delay(TEXT_DEBOUNCE_MS)
            debounce.remove(key)
            flush(key)
        }
    }

    /** Forgets a draft without writing it (the milestone form or a delete supersedes it). */
    private fun drop(key: String) {
        debounce.remove(key)?.cancel()
        pending.remove(key)
        local.update { it.copy(drafts = it.drafts - key) }
    }

    private fun flush(key: String) {
        val write = pending.remove(key) ?: return
        val text = local.value.drafts[key] ?: return
        writes.launch {
            try {
                writeLock.withLock { write(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left as a draft: the next keystroke, stop or clear retries.
                if (key !in pending) pending[key] = write
                return@launch
            }
            // The store now holds the text (local-first): show it unless the user typed more meanwhile.
            if (key !in pending) local.update { l -> if (l.drafts[key] == text) l.copy(drafts = l.drafts - key) else l }
        }
    }

    private fun build(inputs: Inputs, today: String, l: Local): GoalDetailUiState {
        val g = inputs.goals.firstOrNull { it.id == id }
            ?: return GoalDetailUiState(loaded = true, notFound = true, today = today, id = id)
        val hs = inputs.habits
        val all = inputs.tasks
        val projectsById = inputs.projects.associateBy { it.id }
        val p = goalProgress(g, hs, today)
        val st = goalStatus(g, hs, today)
        val reached = g.milestones.count { it.reachedOn != null }
        val (kpiValue, kpiSub) = when (g.type) {
            GoalType.Milestone -> "$reached" to "of ${g.milestones.size} milestones"
            GoalType.Habit -> "$p%" to "habit consistency · 30d"
            else -> goalValueLabel(g) to "of ${goalTargetLabel(g)}"
        }
        val linkedTasks = all.filter { it.goalId == g.id }
        val linkedHabits = hs.filter { it.goalId == g.id }
        val linkedProjects = inputs.projects.filter { it.goalId == g.id }
        val open = linkedTasks.filter { !it.done }.sortedBy { it.due ?: "9" }
        val log = g.progressLog
        val last = log.lastOrNull()
        return GoalDetailUiState(
            loaded = true,
            today = today,
            id = g.id,
            name = g.name,
            eyebrow = "${g.type.label} goal · ${g.term.wire} · Review ${g.reviewEvery.wire.lowercase()}",
            state = g.state,
            type = g.type,
            unit = g.unit,
            kpiValue = kpiValue,
            kpiSub = kpiSub,
            pct = p,
            status = st.label,
            statusTone = statusTone(st),
            onTrack = st == GoalStatus.OnTrack,
            barTone = barTone(st),
            startedLine = "$p% · started ${fmtShort(g.startedOn)} ${g.startedOn.take(4)}",
            dueLine = "Due ${fmtShort(g.due)} ${g.due.take(4)} · ${monthsLeft(g.due, today)}",
            formulaText = formulaText(g),
            formulaField = l.drafts[FORMULA] ?: g.formula ?: "",
            milestones = g.milestones.mapIndexed { i, m -> milestoneRow(g, m, i, today) },
            chartValues = if (log.size > 1) log.map { it.value } else listOf(0.0, g.current),
            chartLabels = if (log.size > 1) {
                val every = (log.size + 4) / 5 // ceil(n / 5)
                log.mapIndexed { i, e -> if (i % every == 0) MON[e.date.substring(5, 7).toInt() - 1] else "" }
            } else listOf("Start", "Now"),
            chartGoal = if (g.type == GoalType.Milestone) g.milestones.size.toDouble() else g.target,
            chartLegend = if (g.unit == "₹") "Saved (₹)" else "Progress",
            lastLogged = last?.let { e -> "Last logged ${fmtDay(e.date, today)}" + (e.note?.takeIf { it.isNotEmpty() }?.let { " · “$it”" } ?: "") },
            nextTask = open.firstOrNull()?.let { taskRowUi(it, all, projectsById, today) },
            nextAction = g.nextAction.ifEmpty { "Define the next step" },
            linked = linkedProjects.map { pr ->
                LinkedUi(LinkedKind.Project, pr.id, pr.name, "Project · ${all.count { it.projectId == pr.id && !it.done }} open", emoji = pr.emoji)
            } + linkedTasks.take(4).map { t ->
                LinkedUi(
                    LinkedKind.Task, t.id, t.title,
                    "Task · " + if (t.done) "done" else t.due?.let { fmtDay(it, today) } ?: "no date",
                    done = t.done,
                )
            } + linkedHabits.map { h ->
                LinkedUi(LinkedKind.Habit, h.id, h.name, "Habit · ${rateDays(h, 30, today).pct}% last 30 days", icon = h.icon)
            },
            linkedCount = linkedProjects.size + linkedTasks.size + linkedHabits.size,
            blockers = blockers(g, st),
            logOpen = l.logOpen,
            log = logProgressUi(g),
            milestoneForm = l.form,
            milestoneDetail = l.detail?.let { mid -> g.milestones.firstOrNull { it.id == mid } }?.let { m ->
                milestoneDetailUi(g, m, open.map { taskRowUi(it, all, projectsById, today) }, l.drafts["ms:${m.id}"], today)
            },
        )
    }

    private fun milestoneRow(g: Goal, m: Milestone, i: Int, today: String): MilestoneRowUi {
        val reachedOn = m.reachedOn
        val sub = if (reachedOn != null) "Reached ${fmtDay(reachedOn, today, relative = false)}" else {
            val date = m.targetDate?.let { "Target ${fmtShort(it)} ${it.take(4)}" } ?: "No date"
            val toGo = m.target?.takeIf { it != 0.0 && g.type == GoalType.Numeric }
                ?.let { " · ${g.unit}${localeIN(max(0.0, it - g.current))} to go" } ?: ""
            date + toGo
        }
        return MilestoneRowUi(
            id = m.id,
            title = m.title,
            sub = sub,
            reached = reachedOn != null,
            now = reachedOn == null && g.milestones.take(i).all { it.reachedOn != null },
        )
    }

    private companion object {
        const val TEXT_DEBOUNCE_MS = 600L
        const val FORMULA = "formula"
    }
}
