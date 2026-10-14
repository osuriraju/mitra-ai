package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.AiAction
import tech.beyondscale.mitra.domain.model.AiActionDraft
import tech.beyondscale.mitra.domain.model.AiMessage
import tech.beyondscale.mitra.domain.model.JournalDraft
import tech.beyondscale.mitra.domain.model.RevertOp
import tech.beyondscale.mitra.domain.model.Suggestion
import tech.beyondscale.mitra.domain.model.SuggestionState

/**
 * The assistant. Chat, suggestions and journal drafts are online-only calls; the conversation and suggestion
 * states live only on the device; the audit log syncs through the outbox.
 */
interface AiRepository {
    /** `GET ai/status` → `enabled` (false when the server has no API key); last known value, true before any. */
    val enabled: Flow<Boolean>

    /** The conversation, oldest first. */
    val messages: Flow<List<AiMessage>>

    /** The audit log, newest first. */
    val actions: Flow<List<AiAction>>

    /** Last loaded suggestions with their local state. */
    val suggestions: Flow<List<Suggestion>>

    /** Re-reads status and `ai/actions`; actions with queued writes are kept. */
    suspend fun refresh(): Outcome<Unit>

    /**
     * Appends the user's message, sends it with the last 10 messages as history (`POST ai/ask`), and appends the
     * reply. On failure appends "Sorry — <reason>" (web `AiScreen.send`) and returns the error.
     */
    suspend fun ask(text: String): Outcome<AiMessage>

    /** Marks a message's proposal applied (true) or back to pending (undo). */
    suspend fun setApplied(messageId: String, applied: Boolean)

    /** Drops the proposal and appends " (cancelled)" to the message text. */
    suspend fun cancelProposal(messageId: String)

    suspend fun clearConversation()

    /** `GET ai/suggestions[?refresh=1]`; ids already known keep their local state. */
    suspend fun loadSuggestions(refresh: Boolean = false): Outcome<Unit>

    suspend fun setSuggestionState(id: String, state: SuggestionState)

    /** Inserts the row locally (stamped now) and queues `POST ai/actions`. */
    suspend fun logAction(draft: AiActionDraft): AiAction

    /**
     * Marks [id] reverted (state + " · reverted"), prepends a "Reverted · By you" row, queues
     * `POST ai/actions/:id/revert`, and returns the stored undo ops for the caller to execute against the other
     * repositories. Unknown or already reverted = empty list.
     */
    suspend fun revert(id: String): List<RevertOp>

    /** `POST ai/journal-draft` (online only). */
    suspend fun journalDraft(date: String): Outcome<JournalDraft>
}
