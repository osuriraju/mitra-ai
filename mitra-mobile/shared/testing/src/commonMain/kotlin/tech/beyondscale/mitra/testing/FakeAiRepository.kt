package tech.beyondscale.mitra.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.AiAction
import tech.beyondscale.mitra.domain.model.AiActionDraft
import tech.beyondscale.mitra.domain.model.AiMessage
import tech.beyondscale.mitra.domain.model.AiReply
import tech.beyondscale.mitra.domain.model.AiWho
import tech.beyondscale.mitra.domain.model.JournalDraft
import tech.beyondscale.mitra.domain.model.RevertOp
import tech.beyondscale.mitra.domain.model.Suggestion
import tech.beyondscale.mitra.domain.model.SuggestionState
import tech.beyondscale.mitra.domain.repository.AiRepository

/**
 * In-memory [AiRepository]. [ask] answers with [nextReply] (or fails with [askError]); suggestions load from
 * [serverSuggestions]; [journalDraft] returns [journalResult]. Calls are recorded in [calls].
 */
class FakeAiRepository(
    actions: List<AiAction> = emptyList(),
    enabled: Boolean = true,
) : AiRepository {
    val enabledFlow = MutableStateFlow(enabled)
    val messageFlow = MutableStateFlow<List<AiMessage>>(emptyList())
    val actionFlow = MutableStateFlow(actions)
    val suggestionFlow = MutableStateFlow<List<Suggestion>>(emptyList())
    override val enabled: Flow<Boolean> = enabledFlow
    override val messages: Flow<List<AiMessage>> = messageFlow
    override val actions: Flow<List<AiAction>> = actionFlow
    override val suggestions: Flow<List<Suggestion>> = suggestionFlow

    val calls = mutableListOf<String>()
    var refreshResult: Outcome<Unit> = Outcome.Ok(Unit)
    var nextReply: AiReply = AiReply("OK")
    var askError: AppError? = null
    var serverSuggestions: List<Suggestion> = emptyList()
    var suggestionsError: AppError? = null
    var journalResult: Outcome<JournalDraft> = Outcome.Ok(JournalDraft("A calm day.", "tasks"))
    /** The "now" stamped on logged actions. */
    var now: String = "2026-10-14T04:30:00.000Z"
    private var seq = 0

    override suspend fun refresh(): Outcome<Unit> { calls += "refresh"; return refreshResult }

    override suspend fun ask(text: String): Outcome<AiMessage> {
        calls += "ask:$text"
        messageFlow.update { it + AiMessage("m${++seq}", AiWho.User, text, createdAt = seq.toLong()) }
        val err = askError
        val reply = if (err == null) {
            AiMessage("m${++seq}", AiWho.Ai, nextReply.text, nextReply.chart, nextReply.proposal, createdAt = seq.toLong())
        } else {
            AiMessage("m${++seq}", AiWho.Ai, "Sorry — ${err.message}", createdAt = seq.toLong())
        }
        messageFlow.update { it + reply }
        return if (err == null) Outcome.Ok(reply) else Outcome.Err(err)
    }

    override suspend fun setApplied(messageId: String, applied: Boolean) {
        calls += "applied:$messageId:$applied"
        messageFlow.update { l -> l.map { if (it.id == messageId) it.copy(applied = applied) else it } }
    }

    override suspend fun cancelProposal(messageId: String) {
        calls += "cancel:$messageId"
        messageFlow.update { l -> l.map { if (it.id == messageId) it.copy(proposal = null, text = it.text + " (cancelled)") else it } }
    }

    override suspend fun clearConversation() { calls += "clear"; messageFlow.value = emptyList() }

    override suspend fun loadSuggestions(refresh: Boolean): Outcome<Unit> {
        calls += "suggestions:$refresh"
        suggestionsError?.let { return Outcome.Err(it) }
        val known = suggestionFlow.value.associate { it.id to it.state }
        suggestionFlow.value = serverSuggestions.map { it.copy(state = known[it.id] ?: SuggestionState.Open) }
        return Outcome.Ok(Unit)
    }

    override suspend fun setSuggestionState(id: String, state: SuggestionState) {
        calls += "suggestion:$id:${state.wire}"
        suggestionFlow.update { l -> l.map { if (it.id == id) it.copy(state = state) else it } }
    }

    override suspend fun logAction(draft: AiActionDraft): AiAction {
        val a = AiAction(
            "a${++seq}", now, draft.action, draft.detail, draft.state, draft.tone, draft.revertible, revert = draft.revert,
        )
        calls += "log:${draft.action}"
        actionFlow.update { listOf(a) + it }
        return a
    }

    override suspend fun revert(id: String): List<RevertOp> {
        calls += "revert:$id"
        val a = actionFlow.value.firstOrNull { it.id == id }
        if (a == null || a.reverted) return emptyList()
        val row = AiAction("a${++seq}", now, "Reverted", a.detail, "By you", "warning", revertible = false)
        actionFlow.update { l -> listOf(row) + l.map { if (it.id == id) it.copy(reverted = true, state = "${it.state} · reverted") else it } }
        return a.revert
    }

    override suspend fun journalDraft(date: String): Outcome<JournalDraft> { calls += "journal:$date"; return journalResult }
}
