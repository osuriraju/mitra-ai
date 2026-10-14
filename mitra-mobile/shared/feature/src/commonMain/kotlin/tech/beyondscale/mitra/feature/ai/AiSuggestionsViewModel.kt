package tech.beyondscale.mitra.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.ai.suggestionActionDraft
import tech.beyondscale.mitra.domain.ai.suggestionsFor
import tech.beyondscale.mitra.domain.model.Suggestion
import tech.beyondscale.mitra.domain.model.SuggestionState
import tech.beyondscale.mitra.domain.repository.AiRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent

data class AiSuggestionsUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = true,
    /** 0 Open · 1 Handled. */
    val tab: Int = 0,
    val openCount: Int = 0,
    val list: List<Suggestion> = emptyList(),
    /** The suggestion whose preview is expanded; Apply is offered only inside it. */
    val previewId: String? = null,
    val busy: Boolean = false,
    val offline: Boolean = false,
)

sealed interface AiSuggestionsAction {
    data class Tab(val index: Int) : AiSuggestionsAction
    data object Refresh : AiSuggestionsAction
    data class TogglePreview(val id: String) : AiSuggestionsAction
    /** Confirm from the open preview. */
    data class Apply(val id: String) : AiSuggestionsAction
    data class Dismiss(val id: String) : AiSuggestionsAction
    data class Restore(val id: String) : AiSuggestionsAction
    data object RestoreAll : AiSuggestionsAction
    data class UndoApply(val id: String, val actionId: String) : AiSuggestionsAction
}

/** Port of the web `AiSuggestionsScreen`: review (preview) → apply, dismiss, restore. */
class AiSuggestionsViewModel(
    private val ai: AiRepository,
    private val runner: AiActionRunner,
) : ViewModel() {
    private data class Local(val tab: Int = 0, val previewId: String? = null, val busy: Boolean = false, val offline: Boolean = false)

    private val local = MutableStateFlow(Local())
    private val queue = EventQueue<AiSuggestionsAction>()
    val events: Flow<UiEvent<AiSuggestionsAction>> = queue.events
    private val applying = mutableSetOf<String>()

    val state: StateFlow<AiSuggestionsUiState> = combine(ai.suggestions, ai.enabled, local) { all, enabled, l ->
        AiSuggestionsUiState(
            loaded = true, enabled = enabled, tab = l.tab, openCount = suggestionsFor(all, true).size,
            list = suggestionsFor(all, l.tab == 0), previewId = l.previewId, busy = l.busy, offline = l.offline,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSuggestionsUiState())

    init {
        viewModelScope.launch {
            if (ai.enabled.first() && ai.suggestions.first().isEmpty()) load(refresh = false, toast = false)
        }
    }

    fun onAction(a: AiSuggestionsAction) {
        when (a) {
            is AiSuggestionsAction.Tab -> local.update { it.copy(tab = a.index.coerceIn(0, 1), previewId = null) }
            AiSuggestionsAction.Refresh -> viewModelScope.launch { load(refresh = true, toast = true) }
            is AiSuggestionsAction.TogglePreview -> local.update { it.copy(previewId = if (it.previewId == a.id) null else a.id) }
            is AiSuggestionsAction.Apply -> apply(a.id)
            is AiSuggestionsAction.Dismiss -> viewModelScope.launch {
                ai.setSuggestionState(a.id, SuggestionState.Dismissed)
                queue.send(UiEvent.Snack("Dismissed · won’t resurface for 30 days", AiSuggestionsAction.Restore(a.id)))
            }
            is AiSuggestionsAction.Restore -> viewModelScope.launch { ai.setSuggestionState(a.id, SuggestionState.Open) }
            AiSuggestionsAction.RestoreAll -> viewModelScope.launch {
                suggestionsFor(ai.suggestions.first(), open = false).forEach { ai.setSuggestionState(it.id, SuggestionState.Open) }
            }
            is AiSuggestionsAction.UndoApply -> viewModelScope.launch {
                runner.runRevert(ai.revert(a.actionId))
                ai.setSuggestionState(a.id, SuggestionState.Open)
            }
        }
    }

    private suspend fun load(refresh: Boolean, toast: Boolean) {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        val r = ai.loadSuggestions(refresh)
        local.update { it.copy(busy = false, offline = r is Outcome.Err && r.error is AppError.Offline) }
        when {
            r is Outcome.Err && r.error !is AppError.Offline -> queue.send(UiEvent.Snack(r.error.message))
            r is Outcome.Ok && toast -> queue.send(UiEvent.Snack("Suggestions refreshed"))
        }
    }

    private fun apply(id: String) {
        // Preview → confirm: Apply only from the expanded preview.
        if (local.value.previewId != id || !applying.add(id)) return
        viewModelScope.launch {
            try {
                val s = ai.suggestions.first().firstOrNull { it.id == id && it.state == SuggestionState.Open } ?: return@launch
                val ops = runner.applySuggestion(s)
                ai.setSuggestionState(id, SuggestionState.Applied)
                val logged = ai.logAction(suggestionActionDraft(s, ops))
                local.update { it.copy(previewId = null) }
                queue.send(UiEvent.Haptic(HapticKind.Confirm))
                queue.send(UiEvent.Snack("Applied", AiSuggestionsAction.UndoApply(id, logged.id)))
            } finally {
                applying.remove(id)
            }
        }
    }
}
