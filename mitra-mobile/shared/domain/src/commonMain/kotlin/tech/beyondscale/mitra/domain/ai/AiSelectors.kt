package tech.beyondscale.mitra.domain.ai

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.fmtClock
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.inr
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.domain.model.AiAction
import tech.beyondscale.mitra.domain.model.AiActionDraft
import tech.beyondscale.mitra.domain.model.AiMessage
import tech.beyondscale.mitra.domain.model.AiProposal
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.RevertOp
import tech.beyondscale.mitra.domain.model.Suggestion
import tech.beyondscale.mitra.domain.model.SuggestionState

/* Display and bookkeeping helpers ported from mitra-frontend/src/screens/ai.tsx and the server's `actionOut`. */

private val BOLD = Regex("\\*\\*(.+?)\\*\\*")

/** `**bold**` runs as (text, isBold) — the web renders them with `<b>` (`md`). */
fun boldSegments(text: String): List<Pair<String, Boolean>> {
    val out = mutableListOf<Pair<String, Boolean>>()
    var last = 0
    BOLD.findAll(text).forEach { m ->
        if (m.range.first > last) out += text.substring(last, m.range.first) to false
        out += m.groupValues[1] to true
        last = m.range.last + 1
    }
    if (last < text.length) out += text.substring(last) to false
    return out
}

/** "Today 4:05 PM" · "Yesterday 9:00 AM" · "Mon 12 Oct 8:30 PM" from the local date and time of an action. */
fun aiWhenLabel(localDate: String, hour: Int, minute: Int, today: String): String =
    "${if (localDate == today) "Today" else fmtDay(localDate, today)} ${fmtClock(hour, minute)}"

/** "tasks, habits, goals, money, notes[, wellness]" — what the assistant can see. */
fun aiScope(shareWellness: Boolean): String =
    (listOf("tasks", "habits", "goals", "money", "notes") + if (shareWellness) listOf("wellness") else emptyList()).joinToString(", ")

/**
 * `history` for `POST ai/ask`: the last 10 messages as (who, text). Like the web, the caller appends the user's new
 * message first, so it is also the last history item.
 */
fun askHistory(messages: List<AiMessage>): List<Pair<String, String>> = messages.takeLast(10).map { it.who.wire to it.text }

fun proposalApplyLabel(p: AiProposal): String = when (p) {
    is AiProposal.Reschedule -> "Apply changes"
    is AiProposal.Plan -> "Create goal + tasks"
    else -> "Save"
}

fun proposalAppliedToast(p: AiProposal): String = when (p) {
    is AiProposal.Expense -> "Expense saved"
    is AiProposal.Task -> "Task added"
    is AiProposal.Plan -> "Goal and plan created"
    is AiProposal.Reschedule -> "Tasks rescheduled"
}

/** What applying a [AiProposal.Plan] creates (web `applyProposal` plan branch). Money in rupees. */
data class PlanBlueprint(
    val goalName: String,
    val target: Double,
    val due: String,
    val term: GoalTerm,
    val nextAction: String,
    /** (title, target, targetDate) × 4. */
    val milestones: List<Triple<String, Double, String>>,
    val taskTitle: String,
    val taskDue: String,
    /** Rupees; the task's `amount`, recurring "Monthly", linked to the new goal. */
    val taskAmount: Double,
)

fun planBlueprint(p: AiProposal.Plan, today: String): PlanBlueprint = PlanBlueprint(
    goalName = p.goalName,
    target = p.target,
    due = addDays(today, p.months * 30),
    term = if (p.months <= 6) GoalTerm.Short else GoalTerm.Medium,
    nextAction = "Transfer ${inr(p.monthly)} on the 2nd",
    milestones = (1..4).map { i ->
        val t = jsRound(p.target * i / 4).toDouble()
        Triple(inr(t), t, addDays(today, jsRound(p.months * 30.0 * i / 4).toInt()))
    },
    taskTitle = "Transfer ${inr(p.monthly)} to savings",
    taskDue = addDays(today, 2),
    taskAmount = p.monthly,
)

/** `{"due":"2026-10-15"}` / `{"due":null}` — the undo patch for a rescheduled task. */
fun duePatchJson(due: String?): String = if (due == null) "{\"due\":null}" else "{\"due\":\"$due\"}"

/**
 * The audit-log row for an applied proposal (web `applyProposal`). [createdIds]: expense → [txId]; task → [taskId];
 * plan → [goalId, taskId]; reschedule → unused. [previousDue] maps each moved task id to its due before the move.
 */
fun proposalActionDraft(
    p: AiProposal,
    today: String,
    createdIds: List<String> = emptyList(),
    previousDue: Map<String, String?> = emptyMap(),
): AiActionDraft = when (p) {
    is AiProposal.Expense -> AiActionDraft(
        "Created expense", "${p.merchant} · ${inr(p.amount)}", revertible = true,
        revert = createdIds.take(1).map { RevertOp(RevertOp.DELETE_TRANSACTION, it) },
    )
    is AiProposal.Task -> AiActionDraft(
        "Created task", p.taskTitle + (p.due?.let { " · " + fmtDay(it, today) } ?: ""), revertible = true,
        revert = createdIds.take(1).map { RevertOp(RevertOp.DELETE_TASK, it) },
    )
    is AiProposal.Plan -> AiActionDraft(
        "Proposed plan", "${p.goalName}: 4 milestones, monthly transfer task", revertible = true,
        revert = listOfNotNull(
            createdIds.getOrNull(0)?.let { RevertOp(RevertOp.DELETE_GOAL, it) },
            createdIds.getOrNull(1)?.let { RevertOp(RevertOp.DELETE_TASK, it) },
        ),
    )
    is AiProposal.Reschedule -> AiActionDraft(
        action = "Rescheduled ${p.moves.size} tasks",
        detail = p.moves.joinToString(" · ") { m -> "${m.title.split(" ").take(2).joinToString(" ")} → ${m.label}" },
        state = "Applied",
        tone = "warning",
        revertible = true,
        revert = p.moves.map { RevertOp(RevertOp.UPDATE_TASK, it.id, duePatchJson(previousDue[it.id])) },
    )
}

/** The audit-log row for an applied suggestion (web `AiSuggestionsScreen.apply`). */
fun suggestionActionDraft(s: Suggestion, ops: List<RevertOp>): AiActionDraft =
    AiActionDraft("Applied suggestion", s.title, revertible = ops.isNotEmpty(), revert = ops)

/** Suggestions for the Open (true) or Handled (false) tab. */
fun suggestionsFor(list: List<Suggestion>, open: Boolean): List<Suggestion> =
    list.filter { (it.state == SuggestionState.Open) == open }

/** "All types" plus each distinct first word of an action ("Created", "Rescheduled", …), in log order. */
fun aiLogTypes(actions: List<AiAction>): List<String> = listOf("All types") + actions.map { it.action.split(" ")[0] }.distinct()

/**
 * The log filtered by type and, unless [sinceDay] is null, to actions on or after that local day.
 * [localDayOf] maps an action to its local ISO day.
 */
fun filterAiLog(actions: List<AiAction>, type: String, sinceDay: String?, localDayOf: (AiAction) -> String): List<AiAction> =
    actions.filter { type == "All types" || it.action.startsWith(type) }
        .filter { sinceDay == null || localDayOf(it) >= sinceDay }

/** The CSV the web exports (`when,action,detail,state`; double quotes in detail become single quotes). */
fun aiLogCsv(actions: List<AiAction>, whenOf: (AiAction) -> String): String =
    "when,action,detail,state\n" + actions.joinToString("\n") {
        "\"${whenOf(it)}\",\"${it.action}\",\"${it.detail.replace("\"", "'")}\",\"${it.state}\""
    }
