package tech.beyondscale.mitra.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.data.db.Activity
import tech.beyondscale.mitra.data.db.Ai_actions
import tech.beyondscale.mitra.data.db.Ai_messages
import tech.beyondscale.mitra.data.db.Suggestions
import tech.beyondscale.mitra.domain.model.ActivityItem
import tech.beyondscale.mitra.domain.model.AiAction
import tech.beyondscale.mitra.domain.model.AiMessage
import tech.beyondscale.mitra.domain.model.AiProposal
import tech.beyondscale.mitra.domain.model.AiWho
import tech.beyondscale.mitra.domain.model.RescheduleMove
import tech.beyondscale.mitra.domain.model.RevertOp
import tech.beyondscale.mitra.domain.model.Suggestion
import tech.beyondscale.mitra.domain.model.SuggestionAction
import tech.beyondscale.mitra.domain.model.SuggestionState
import tech.beyondscale.mitra.domain.model.TriageMove

/*
 * Row mappers for activity, ai_actions, ai_messages, suggestions, plus the JSON codecs for the server's proposal,
 * suggestion-action and revert-op objects (usable by the DTO layer too). Decoders never throw: anything unreadable
 * becomes null / is dropped. No SQL cascades: none of these tables has child rows.
 */

private val lenient = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
private fun parseObject(s: String?): JsonObject? = s?.let { runCatching { lenient.parseToJsonElement(it).jsonObject }.getOrNull() }
private fun parseArray(s: String?): JsonArray? = s?.let { runCatching { lenient.parseToJsonElement(it).jsonArray }.getOrNull() }

/* ---------- proposal ---------- */

fun aiProposalFromJson(o: JsonObject): AiProposal? = runCatching {
    val title = o.str("title") ?: ""
    val sub = o.str("sub") ?: ""
    when (o.str("kind")) {
        "expense" -> AiProposal.Expense(title, sub, o.num("amount")!!, o.str("categoryId")!!, o.str("accountId")!!, o.str("merchant") ?: "", o.str("note"))
        "task" -> AiProposal.Task(title, sub, o.str("taskTitle")!!, o.str("due"), o.str("time"), o.str("projectId"))
        "plan" -> AiProposal.Plan(title, sub, o.str("goalName")!!, o.num("target")!!, o.int("months")!!, o.num("monthly")!!)
        "reschedule" -> AiProposal.Reschedule(
            title, sub,
            o["moves"]!!.jsonArray.map { m ->
                val mo = m.jsonObject
                RescheduleMove(mo.str("id")!!, mo.str("title") ?: "", mo.str("to")!!, mo.str("label") ?: "")
            },
        )
        else -> null
    }
}.getOrNull()

fun AiProposal.toJson(): JsonObject = buildJsonObject {
    put("kind", kind)
    put("title", title)
    put("sub", sub)
    when (val p = this@toJson) {
        is AiProposal.Expense -> {
            put("amount", p.amount); put("categoryId", p.categoryId); put("accountId", p.accountId); put("merchant", p.merchant)
            p.note?.let { put("note", it) }
        }
        is AiProposal.Task -> {
            put("taskTitle", p.taskTitle)
            p.due?.let { put("due", it) }; p.time?.let { put("time", it) }; p.projectId?.let { put("projectId", it) }
        }
        is AiProposal.Plan -> { put("goalName", p.goalName); put("target", p.target); put("months", p.months); put("monthly", p.monthly) }
        is AiProposal.Reschedule -> put(
            "moves",
            JsonArray(p.moves.map { m -> buildJsonObject { put("id", m.id); put("title", m.title); put("to", m.to); put("label", m.label) } }),
        )
    }
}

/* ---------- suggestion action ---------- */

fun suggestionActionFromJson(o: JsonObject): SuggestionAction? = runCatching {
    when (o.str("kind")) {
        "set_habit_reminder" -> SuggestionAction.SetHabitReminder(o.str("habitId")!!, o.str("time")!!)
        "add_recurring" -> SuggestionAction.AddRecurring(o.str("name") ?: "", o.num("amount")!!, o.int("day")!!, o.str("categoryId"))
        "add_subtasks" -> SuggestionAction.AddSubtasks(o.str("taskId")!!, o["titles"]!!.jsonArray.map { it.jsonPrimitive.content })
        "triage_inbox" -> SuggestionAction.TriageInbox(
            o["moves"]!!.jsonArray.map { m -> val mo = m.jsonObject; TriageMove(mo.str("taskId")!!, mo.str("projectId"), mo.str("due")) },
        )
        "pause_goal" -> SuggestionAction.PauseGoal(o.str("goalId")!!)
        "set_budget" -> SuggestionAction.SetBudget(o.str("categoryId")!!, o.num("amount")!!)
        "add_task" -> SuggestionAction.AddTask(o.str("title") ?: "", o.str("due"))
        else -> null
    }
}.getOrNull()

fun SuggestionAction.toJson(): JsonObject = buildJsonObject {
    when (val a = this@toJson) {
        is SuggestionAction.SetHabitReminder -> { put("kind", "set_habit_reminder"); put("habitId", a.habitId); put("time", a.time) }
        is SuggestionAction.AddRecurring -> {
            put("kind", "add_recurring"); put("name", a.name); put("amount", a.amount); put("day", a.day)
            a.categoryId?.let { put("categoryId", it) }
        }
        is SuggestionAction.AddSubtasks -> { put("kind", "add_subtasks"); put("taskId", a.taskId); put("titles", JsonArray(a.titles.map(::JsonPrimitive))) }
        is SuggestionAction.TriageInbox -> {
            put("kind", "triage_inbox")
            put("moves", JsonArray(a.moves.map { m -> buildJsonObject { put("taskId", m.taskId); put("projectId", m.projectId); put("due", m.due) } }))
        }
        is SuggestionAction.PauseGoal -> { put("kind", "pause_goal"); put("goalId", a.goalId) }
        is SuggestionAction.SetBudget -> { put("kind", "set_budget"); put("categoryId", a.categoryId); put("amount", a.amount) }
        is SuggestionAction.AddTask -> { put("kind", "add_task"); put("title", a.title); put("due", a.due) }
    }
}

/* ---------- revert ops ---------- */

/** The server's `revert` array (or anything else → empty); malformed ops are dropped. */
fun revertOpsFromJson(element: JsonElement?): List<RevertOp> = (element as? JsonArray).orEmpty().mapNotNull { e ->
    val o = e as? JsonObject ?: return@mapNotNull null
    val op = o.str("op") ?: return@mapNotNull null
    val id = o.str("id") ?: return@mapNotNull null
    RevertOp(op, id, (o["patch"] as? JsonObject)?.toString())
}

fun List<RevertOp>.toJson(): JsonArray = JsonArray(
    map { r ->
        buildJsonObject {
            put("op", r.op); put("id", r.id)
            parseObject(r.patchJson)?.let { put("patch", it) }
        }
    },
)

/* ---------- rows ---------- */

fun Activity.toDomain(): ActivityItem = ActivityItem(id, text, icon, tone, module, entityId, at)

fun ActivityItem.toRow(): Activity = Activity(id, text, icon, tone, module, entityId, at)

fun Ai_actions.toDomain(): AiAction = AiAction(
    id, at, action, detail, state, tone, revertible != 0L, reverted != 0L, revertOpsFromJson(parseArray(revertJson)),
)

fun AiAction.toRow(): Ai_actions = Ai_actions(
    id, at, action, detail, state, tone, if (revertible) 1L else 0L, if (reverted) 1L else 0L,
    if (revert.isEmpty()) null else revert.toJson().toString(),
)

fun Ai_messages.toDomain(): AiMessage = AiMessage(
    id = id, who = AiWho.fromWire(who), text = text,
    chart = parseArray(chartJson)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull },
    proposal = parseObject(proposalJson)?.let(::aiProposalFromJson),
    applied = applied != 0L, createdAt = createdAt,
)

fun AiMessage.toRow(): Ai_messages = Ai_messages(
    id, who.wire, text, chart?.let { c -> JsonArray(c.map(::JsonPrimitive)).toString() }, proposal?.toJson()?.toString(),
    if (applied) 1L else 0L, createdAt,
)

fun Suggestions.toDomain(): Suggestion = Suggestion(
    id, icon, title, why, source, tone, preview, parseObject(actionJson)?.let(::suggestionActionFromJson), SuggestionState.fromWire(state),
)

fun Suggestion.toRow(position: Long): Suggestions = Suggestions(
    id, icon, title, why, source, tone, preview, action?.toJson()?.toString(), state.wire, position,
)
