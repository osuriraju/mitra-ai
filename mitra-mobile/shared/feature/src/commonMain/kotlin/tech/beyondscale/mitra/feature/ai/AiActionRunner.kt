package tech.beyondscale.mitra.feature.ai

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.localNow
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.ai.planBlueprint
import tech.beyondscale.mitra.domain.ai.proposalActionDraft
import tech.beyondscale.mitra.domain.model.AiActionDraft
import tech.beyondscale.mitra.domain.model.AiProposal
import tech.beyondscale.mitra.domain.model.CategoryChange
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalDraft
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.HabitChange
import tech.beyondscale.mitra.domain.model.MilestoneDraft
import tech.beyondscale.mitra.domain.model.RecurringDraft
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.RevertOp
import tech.beyondscale.mitra.domain.model.Suggestion
import tech.beyondscale.mitra.domain.model.SuggestionAction
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.model.TxDraft
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.model.TxSource
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository

/**
 * Applies confirmed AI changes through the module repositories (offline-first, each queues its own server write), as
 * the web `applyProposal` / `applySuggestion` / `runRevert` do on the client. Amounts from the assistant are rupees.
 */
class AiActionRunner(
    private val tasks: TasksRepository,
    private val money: MoneyRepository,
    private val goals: GoalsRepository,
    private val habits: HabitsRepository,
    private val clock: AppClock,
) {
    /** Applies [p] and returns the audit-log row describing it (with its undo ops). */
    suspend fun applyProposal(p: AiProposal): AiActionDraft {
        val today = clock.today()
        return when (p) {
            is AiProposal.Expense -> {
                val account = money.accounts.first().firstOrNull { it.id == p.accountId }?.id
                    ?: money.accounts.first().firstOrNull()?.id ?: p.accountId
                val now = clock.localNow()
                val tx = money.addTransaction(
                    TxDraft(
                        TxKind.Expense, jsRound(p.amount * 100), p.categoryId, account, null, p.merchant, p.note, today,
                        "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}", TxSource.Ai,
                    ),
                )
                proposalActionDraft(p, today, listOf(tx.id))
            }
            is AiProposal.Task -> {
                val t = tasks.create(TaskDraft(title = p.taskTitle, due = p.due, time = p.time, projectId = p.projectId))
                proposalActionDraft(p, today, listOf(t.id))
            }
            is AiProposal.Plan -> {
                val b = planBlueprint(p, today)
                val g = goals.create(
                    GoalDraft(name = b.goalName, type = GoalType.Numeric, term = b.term, due = b.due, target = b.target, unit = "₹", nextAction = b.nextAction),
                )
                b.milestones.forEach { (title, target, date) -> goals.addMilestone(g.id, MilestoneDraft(title, target, date)) }
                // TaskDraft has no `amount`: the monthly figure stays in the title.
                val t = tasks.create(TaskDraft(title = b.taskTitle, due = b.taskDue, recurring = "Monthly", goalId = g.id))
                proposalActionDraft(p, today, listOf(g.id, t.id))
            }
            is AiProposal.Reschedule -> {
                val current = tasks.tasks.first().associateBy { it.id }
                val before = p.moves.associate { it.id to current[it.id]?.due }
                p.moves.forEach { m -> if (m.id in current) tasks.update(m.id, listOf(TaskChange.Due(m.to))) }
                proposalActionDraft(p, today, previousDue = before)
            }
        }
    }

    /** Applies a suggestion; returns its undo ops (empty when its target no longer exists). */
    suspend fun applySuggestion(s: Suggestion): List<RevertOp> = when (val a = s.action) {
        null -> emptyList()
        is SuggestionAction.SetHabitReminder -> {
            val h = habits.habits.first().firstOrNull { it.id == a.habitId }
            if (h == null) emptyList() else {
                habits.update(h.id, listOf(HabitChange.Reminder(a.time)))
                listOf(RevertOp(RevertOp.UPDATE_HABIT, h.id, patch("reminder" to h.reminder)))
            }
        }
        is SuggestionAction.AddRecurring -> {
            val r = money.addRecurring(
                RecurringDraft(
                    a.name, "🔁", jsRound(a.amount * 100), a.day, RecurringKind.Bill,
                    categoryId = a.categoryId, accountId = money.accounts.first().firstOrNull()?.id,
                ),
            )
            listOf(RevertOp(RevertOp.DELETE_RECURRING, r.id))
        }
        is SuggestionAction.AddSubtasks -> {
            val parent = tasks.tasks.first().firstOrNull { it.id == a.taskId }
            if (parent == null) emptyList() else a.titles.map { title ->
                RevertOp(RevertOp.DELETE_TASK, tasks.create(TaskDraft(title = title, due = parent.due, parentId = parent.id)).id)
            }
        }
        is SuggestionAction.TriageInbox -> {
            val current = tasks.tasks.first().associateBy { it.id }
            a.moves.mapNotNull { m ->
                val t = current[m.taskId] ?: return@mapNotNull null
                tasks.update(t.id, listOf(TaskChange.ProjectId(m.projectId), TaskChange.Due(m.due)))
                RevertOp(RevertOp.UPDATE_TASK, t.id, patch("projectId" to t.projectId, "due" to t.due))
            }
        }
        is SuggestionAction.PauseGoal -> {
            val g = goals.goals.first().firstOrNull { it.id == a.goalId }
            if (g == null) emptyList() else {
                goals.update(g.id, listOf(GoalChange.State(GoalState.Paused)))
                listOf(RevertOp(RevertOp.UPDATE_GOAL, g.id, patch("status" to g.state.wire)))
            }
        }
        is SuggestionAction.SetBudget -> {
            val c = money.categories.first().firstOrNull { it.id == a.categoryId }
            if (c == null) emptyList() else {
                money.updateCategory(c.id, listOf(CategoryChange.Budget(jsRound(a.amount * 100))))
                // Web field names and units: `budget` in rupees.
                listOf(RevertOp(RevertOp.UPDATE_CATEGORY, c.id, budgetPatch(c.budgetPaise)))
            }
        }
        is SuggestionAction.AddTask -> listOf(RevertOp(RevertOp.DELETE_TASK, tasks.create(TaskDraft(title = a.title, due = a.due)).id))
    }

    /** Executes undo ops (web `runRevert`). `patch` uses web field names and units. Unknown ops are skipped. */
    suspend fun runRevert(ops: List<RevertOp>) {
        ops.forEach { o ->
            val p = parseFlatPatch(o.patchJson)
            when (o.op) {
                RevertOp.DELETE_TRANSACTION -> money.deleteTransaction(o.id)
                RevertOp.DELETE_TASK -> tasks.delete(o.id)
                RevertOp.DELETE_GOAL -> goals.delete(o.id)
                RevertOp.DELETE_HABIT -> habits.delete(o.id)
                RevertOp.DELETE_RECURRING -> money.deleteRecurring(o.id)
                RevertOp.UPDATE_TASK -> taskChanges(p).takeIf { it.isNotEmpty() }?.let { tasks.update(o.id, it) }
                RevertOp.UPDATE_HABIT -> if ("reminder" in p) habits.update(o.id, listOf(HabitChange.Reminder(p["reminder"])))
                RevertOp.UPDATE_GOAL -> p["status"]?.let { goals.update(o.id, listOf(GoalChange.State(GoalState.fromWire(it)))) }
                RevertOp.UPDATE_CATEGORY -> if ("budget" in p) {
                    money.updateCategory(o.id, listOf(CategoryChange.Budget(p["budget"]?.toDoubleOrNull()?.let { jsRound(it * 100) })))
                }
                RevertOp.UPDATE_RECURRING -> p["reminder"]?.toBooleanStrictOrNull()?.let { money.setRecurringReminder(o.id, it) }
            }
        }
    }

    private fun taskChanges(p: Map<String, String?>): List<TaskChange> = buildList {
        if ("due" in p) add(TaskChange.Due(p["due"]))
        if ("projectId" in p) add(TaskChange.ProjectId(p["projectId"]))
    }

    private fun patch(vararg kv: Pair<String, String?>): String =
        JsonObject(kv.associate { (k, v) -> k to JsonPrimitive(v) }).toString()

    /** Web `budget` is rupees. */
    private fun budgetPatch(paise: Long?): String = buildJsonObject { put("budget", paise?.let { it / 100.0 }) }.toString()
}

/**
 * A revert `patch` as key → raw value (strings unquoted, `null` → null, numbers/booleans as text); patches are flat
 * objects of scalars. Anything unreadable is an empty map.
 */
internal fun parseFlatPatch(json: String?): Map<String, String?> {
    if (json == null) return emptyMap()
    val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyMap()
    return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content }
}
