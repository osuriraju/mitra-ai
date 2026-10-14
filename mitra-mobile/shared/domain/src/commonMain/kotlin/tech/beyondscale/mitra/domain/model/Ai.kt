package tech.beyondscale.mitra.domain.model

/* AI models — wire shapes as mitra-backend/src/ai/{schemas,ai.service}.ts and mitra-frontend/src/store/types.ts. */

enum class AiWho(val wire: String) {
    User("user"), Ai("ai");

    companion object { fun fromWire(s: String?): AiWho = entries.firstOrNull { it.wire == s } ?: Ai }
}

/** A reviewable change the assistant proposes (server `enrich`); nothing is applied until the user confirms. */
sealed interface AiProposal {
    val kind: String
    val title: String
    val sub: String

    /** [amount] is whole rupees as the model sent it (convert to paise when applying). */
    data class Expense(
        override val title: String,
        override val sub: String,
        val amount: Double,
        val categoryId: String,
        val accountId: String,
        val merchant: String,
        val note: String? = null,
    ) : AiProposal { override val kind get() = "expense" }

    data class Task(
        override val title: String,
        override val sub: String,
        val taskTitle: String,
        val due: String? = null,
        val time: String? = null,
        val projectId: String? = null,
    ) : AiProposal { override val kind get() = "task" }

    /** Rupees; `months` 1..120. */
    data class Plan(
        override val title: String,
        override val sub: String,
        val goalName: String,
        val target: Double,
        val months: Int,
        val monthly: Double,
    ) : AiProposal { override val kind get() = "plan" }

    data class Reschedule(
        override val title: String,
        override val sub: String,
        val moves: List<RescheduleMove>,
    ) : AiProposal { override val kind get() = "reschedule" }
}

/** [label] is the server's day label for [to] ("Tomorrow", "Fri 16 Oct"). */
data class RescheduleMove(val id: String, val title: String, val to: String, val label: String)

/** `POST ai/ask` → `{ text, chart?, proposal? }`. */
data class AiReply(val text: String, val chart: List<Double>? = null, val proposal: AiProposal? = null)

/**
 * One chat bubble — device-local only (the server keeps no chat history). A cancelled proposal is dropped and
 * " (cancelled)" appended to [text] (web `Preview` Cancel).
 */
data class AiMessage(
    val id: String,
    val who: AiWho,
    val text: String,
    val chart: List<Double>? = null,
    val proposal: AiProposal? = null,
    val applied: Boolean = false,
    /** Epoch millis; conversation order. */
    val createdAt: Long = 0,
)

/**
 * A client-executable undo step stored with an AI action (web `RevertOp`). [op] is one of the `OP_*` constants;
 * [patchJson] is the `patch` object for update ops, as raw JSON with **web field names and units** (e.g. a
 * category `budget` is rupees, a task `due` may be `null`).
 */
data class RevertOp(val op: String, val id: String, val patchJson: String? = null) {
    companion object {
        const val DELETE_TRANSACTION = "deleteTransaction"
        const val DELETE_TASK = "deleteTask"
        const val DELETE_GOAL = "deleteGoal"
        const val DELETE_HABIT = "deleteHabit"
        const val DELETE_RECURRING = "deleteRecurring"
        const val UPDATE_TASK = "updateTask"
        const val UPDATE_HABIT = "updateHabit"
        const val UPDATE_GOAL = "updateGoal"
        const val UPDATE_CATEGORY = "updateCategory"
        const val UPDATE_RECURRING = "updateRecurring"
    }
}

/** One row of the append-only AI audit log (`GET ai/actions`, newest first). */
data class AiAction(
    val id: String,
    /** ISO instant ("2026-10-14T04:30:00.000Z"); format locally with [aiWhenLabel], not the server's `when`. */
    val at: String,
    val action: String,
    val detail: String = "",
    val state: String = "Approved by you",
    val tone: String = "success",
    val revertible: Boolean = false,
    val reverted: Boolean = false,
    val revert: List<RevertOp> = emptyList(),
)

/** `POST ai/actions` body. Server limits: action 1..80, detail ≤ 500, state ≤ 60, tone ≤ 20, ≤ 50 revert ops. */
data class AiActionDraft(
    val action: String,
    val detail: String = "",
    val state: String = "Approved by you",
    val tone: String = "success",
    val revertible: Boolean = false,
    val revert: List<RevertOp> = emptyList(),
)

enum class SuggestionState(val wire: String) {
    Open("open"), Applied("applied"), Dismissed("dismissed");

    companion object { fun fromWire(s: String?): SuggestionState = entries.firstOrNull { it.wire == s } ?: Open }
}

/** What applying a suggestion does (server `SuggestionsReplySchema.action`). Amounts are rupees. */
sealed interface SuggestionAction {
    data class SetHabitReminder(val habitId: String, val time: String) : SuggestionAction
    data class AddRecurring(val name: String, val amount: Double, val day: Int, val categoryId: String? = null) : SuggestionAction
    data class AddSubtasks(val taskId: String, val titles: List<String>) : SuggestionAction
    data class TriageInbox(val moves: List<TriageMove>) : SuggestionAction
    data class PauseGoal(val goalId: String) : SuggestionAction
    data class SetBudget(val categoryId: String, val amount: Double) : SuggestionAction
    data class AddTask(val title: String, val due: String? = null) : SuggestionAction
}

data class TriageMove(val taskId: String, val projectId: String? = null, val due: String? = null)

/**
 * `GET ai/suggestions` row (`{ id, ic, t, p, src, tone, preview, action }`). [state] is device-local: the server
 * does not store it, so a refresh keeps the local state of ids it already knows (web `loadSuggestions`).
 */
data class Suggestion(
    val id: String,
    val icon: String,
    val title: String,
    val why: String,
    val source: String,
    val tone: String = "",
    val preview: String? = null,
    val action: SuggestionAction? = null,
    val state: SuggestionState = SuggestionState.Open,
)

/** `POST ai/journal-draft` → `{ text, sources }` (also the shape of the offline [localJournalDraft]). */
data class JournalDraft(val text: String, val sources: String)

/** `GET activity` row — the server's feed of important actions (module, icon, tone as the web uses them). */
data class ActivityItem(
    val id: String,
    val text: String,
    val icon: String = "circle",
    val tone: String = "",
    val module: String? = null,
    val entityId: String? = null,
    /** ISO instant. */
    val at: String,
)
