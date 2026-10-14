package tech.beyondscale.mitra.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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
import kotlinx.datetime.toLocalDateTime
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.ai.aiWhenLabel
import tech.beyondscale.mitra.domain.ai.proposalAppliedToast
import tech.beyondscale.mitra.domain.ai.suggestionsFor
import tech.beyondscale.mitra.domain.model.AiAction
import tech.beyondscale.mitra.domain.model.AiProposal
import tech.beyondscale.mitra.domain.model.AiWho
import tech.beyondscale.mitra.domain.model.Suggestion
import tech.beyondscale.mitra.domain.repository.AiRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import kotlin.time.Instant

/** One chat bubble as shown. [text] is the revealed part while [revealing]. */
data class AiBubbleUi(
    val id: String,
    val who: AiWho,
    val text: String,
    val revealing: Boolean = false,
    val chart: List<Double>? = null,
    val proposal: AiProposal? = null,
    val applied: Boolean = false,
    /** Web: an AI answer with a chart or bold figures shows "Sources: …". */
    val showSources: Boolean = false,
)

data class AiChatUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = true,
    /** The last ask failed for lack of a network: the screen shows an offline banner. */
    val offline: Boolean = false,
    /** Waiting for `ai/ask` (the typing dots). */
    val typing: Boolean = false,
    val messages: List<AiBubbleUi> = emptyList(),
    /** Proposal message ids being applied (buttons disabled). */
    val confirming: Set<String> = emptySet(),
    val openSuggestions: List<Suggestion> = emptyList(),
    val logCount: Int = 0,
    val prompts: List<String> = AI_PROMPTS,
)

sealed interface AiChatAction {
    data class Send(val text: String) : AiChatAction
    /** The card's primary button — the card itself is the preview; nothing changes before this. */
    data class Confirm(val messageId: String) : AiChatAction
    data class Cancel(val messageId: String) : AiChatAction
    /** Undo from the snackbar: reverts the logged action (audited) and puts the card back to pending. */
    data class UndoApply(val messageId: String, val actionId: String) : AiChatAction
    data object SkipReveal : AiChatAction
    data object Clear : AiChatAction
}

/** The web's example prompts under the conversation. */
val AI_PROMPTS = listOf(
    "Spent ₹450 on dinner", "I have too much to do today", "How productive was I this week?",
    "Plan my goal of saving ₹1 lakh in six months", "What did I spend on food?", "What goals are slipping?",
    "Remind me to call John tomorrow at 6",
)

/** Port of the web `AiScreen`: chat with progressive reveal and inline proposal cards (preview → confirm). */
class AiChatViewModel(
    private val ai: AiRepository,
    private val runner: AiActionRunner,
) : ViewModel() {
    private data class Local(
        val typing: Boolean = false,
        val offline: Boolean = false,
        val confirming: Set<String> = emptySet(),
        /** (message id, visible chars) of the reply being revealed. */
        val reveal: Pair<String, Int>? = null,
    )

    private val local = MutableStateFlow(Local())
    private val queue = EventQueue<AiChatAction>()
    val events: Flow<UiEvent<AiChatAction>> = queue.events
    private var revealJob: Job? = null

    val state: StateFlow<AiChatUiState> =
        combine(ai.messages, ai.enabled, ai.suggestions, ai.actions, local) { messages, enabled, suggestions, actions, l ->
            AiChatUiState(
                loaded = true,
                enabled = enabled,
                offline = l.offline,
                typing = l.typing,
                messages = messages.map { m ->
                    val r = l.reveal?.takeIf { it.first == m.id }
                    AiBubbleUi(
                        m.id, m.who, if (r != null) m.text.take(r.second) else m.text, r != null,
                        // Card and chart appear once the text is fully revealed.
                        if (r != null) null else m.chart, if (r != null) null else m.proposal, m.applied,
                        showSources = m.who == AiWho.Ai && (m.chart != null || "**" in m.text),
                    )
                },
                confirming = l.confirming,
                openSuggestions = suggestionsFor(suggestions, open = true).take(3),
                logCount = actions.size,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiChatUiState())

    init {
        // Web: load suggestions once when AI is on and none are cached. Failures are silent here.
        viewModelScope.launch {
            if (ai.enabled.first() && ai.suggestions.first().isEmpty()) ai.loadSuggestions()
        }
    }

    fun onAction(a: AiChatAction) {
        when (a) {
            is AiChatAction.Send -> send(a.text)
            is AiChatAction.Confirm -> confirm(a.messageId)
            is AiChatAction.Cancel -> viewModelScope.launch { ai.cancelProposal(a.messageId) }
            is AiChatAction.UndoApply -> viewModelScope.launch {
                runner.runRevert(ai.revert(a.actionId))
                ai.setApplied(a.messageId, false)
            }
            AiChatAction.SkipReveal -> { revealJob?.cancel(); local.update { it.copy(reveal = null) } }
            AiChatAction.Clear -> viewModelScope.launch {
                revealJob?.cancel()
                local.update { it.copy(reveal = null) }
                ai.clearConversation()
            }
        }
    }

    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || local.value.typing) return
        revealJob?.cancel()
        local.update { it.copy(typing = true, reveal = null) }
        viewModelScope.launch {
            val r = ai.ask(text)
            local.update {
                it.copy(typing = false, offline = r is Outcome.Err && r.error is AppError.Offline)
            }
            if (r is Outcome.Ok) reveal(r.value.id, r.value.text.length)
        }
    }

    /** Reveals the reply a few characters per frame (≈1 s for a long answer). */
    private fun reveal(id: String, length: Int) {
        if (length == 0) return
        val step = maxOf(REVEAL_MIN_STEP, length / REVEAL_FRAMES)
        local.update { it.copy(reveal = id to 0) }
        revealJob = viewModelScope.launch {
            var shown = 0
            while (shown < length) {
                delay(REVEAL_TICK_MS)
                shown = minOf(length, shown + step)
                local.update { it.copy(reveal = id to shown) }
            }
            local.update { if (it.reveal?.first == id) it.copy(reveal = null) else it }
        }
    }

    private fun confirm(messageId: String) {
        if (messageId in local.value.confirming) return
        local.update { it.copy(confirming = it.confirming + messageId) }
        viewModelScope.launch {
            try {
                val m = ai.messages.first().firstOrNull { it.id == messageId } ?: return@launch
                val p = m.proposal ?: return@launch
                if (m.applied) return@launch
                val draft = runner.applyProposal(p)
                val logged = ai.logAction(draft)
                ai.setApplied(messageId, true)
                queue.send(UiEvent.Haptic(HapticKind.Confirm))
                queue.send(UiEvent.Snack(proposalAppliedToast(p), AiChatAction.UndoApply(messageId, logged.id)))
            } finally {
                local.update { it.copy(confirming = it.confirming - messageId) }
            }
        }
    }

    companion object {
        const val REVEAL_TICK_MS = 16L
        const val REVEAL_FRAMES = 60
        const val REVEAL_MIN_STEP = 2
    }
}

/** "Today 4:05 PM" from an action's ISO instant, in the device zone (never the server's `when`). */
fun aiWhen(a: AiAction, clock: AppClock): String {
    val t = runCatching { Instant.parse(a.at).toLocalDateTime(clock.zone) }.getOrNull() ?: return ""
    return aiWhenLabel(t.date.toString(), t.hour, t.minute, clock.today())
}

/** The local day of an action's instant ("" when unreadable). */
fun aiDay(a: AiAction, clock: AppClock): String =
    runCatching { Instant.parse(a.at).toLocalDateTime(clock.zone).date.toString() }.getOrDefault("")
