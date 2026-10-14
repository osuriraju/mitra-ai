package tech.beyondscale.mitra.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.domain.ai.aiLogCsv
import tech.beyondscale.mitra.domain.ai.aiLogTypes
import tech.beyondscale.mitra.domain.ai.filterAiLog
import tech.beyondscale.mitra.domain.repository.AiRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent

data class AiLogRowUi(
    val id: String,
    val action: String,
    val detail: String,
    /** "Today 4:05 PM · Approved by you", computed on device from `at`. */
    val sub: String,
    val tone: String,
    val isRevertRow: Boolean,
    val canRevert: Boolean,
    val reverted: Boolean,
)

data class AiLogUiState(
    val loaded: Boolean = false,
    val types: List<String> = listOf("All types"),
    val type: String = "All types",
    val allTime: Boolean = false,
    val rows: List<AiLogRowUi> = emptyList(),
    val total: Int = 0,
    val revertedCount: Int = 0,
    /** The whole log as the web's CSV export (for a share sheet). */
    val csv: String = "",
    val refreshing: Boolean = false,
)

sealed interface AiLogAction {
    /** Cycles to the next type, as the web pill does. */
    data object NextType : AiLogAction
    data object ToggleRange : AiLogAction
    data class Revert(val id: String) : AiLogAction
    data object Refresh : AiLogAction
}

/** Port of the web `AiLogScreen`: append-only AI audit log with revert. */
class AiLogViewModel(
    private val ai: AiRepository,
    private val runner: AiActionRunner,
    private val clock: AppClock,
) : ViewModel() {
    private data class Local(val type: String = "All types", val allTime: Boolean = false, val refreshing: Boolean = false)

    private val local = MutableStateFlow(Local())
    private val queue = EventQueue<AiLogAction>()
    val events: Flow<UiEvent<AiLogAction>> = queue.events
    private val reverting = mutableSetOf<String>()

    val state: StateFlow<AiLogUiState> = combine(ai.actions, clock.dayFlow(), local) { actions, today, l ->
        val types = aiLogTypes(actions)
        val type = if (l.type in types) l.type else "All types"
        val list = filterAiLog(actions, type, if (l.allTime) null else addDays(today, -30)) { aiDay(it, clock) }
        AiLogUiState(
            loaded = true, types = types, type = type, allTime = l.allTime,
            rows = list.map { a ->
                AiLogRowUi(
                    a.id, a.action, a.detail, "${aiWhen(a, clock)} · ${a.state}", a.tone, a.action == "Reverted",
                    a.revertible && !a.reverted, a.reverted,
                )
            },
            total = actions.size, revertedCount = actions.count { it.reverted },
            csv = aiLogCsv(actions) { aiWhen(it, clock) }, refreshing = l.refreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiLogUiState())

    fun onAction(a: AiLogAction) {
        when (a) {
            AiLogAction.NextType -> local.update { l ->
                val types = state.value.types
                l.copy(type = types[(types.indexOf(l.type) + 1).mod(types.size)])
            }
            AiLogAction.ToggleRange -> local.update { it.copy(allTime = !it.allTime) }
            is AiLogAction.Revert -> revert(a.id)
            AiLogAction.Refresh -> viewModelScope.launch {
                local.update { it.copy(refreshing = true) }
                val r = ai.refresh()
                local.update { it.copy(refreshing = false) }
                if (r is Outcome.Err) queue.send(UiEvent.Snack(r.error.message))
            }
        }
    }

    /** Undo ops run once, here, from the locally stored copy; the server's revert response ops are never replayed. */
    private fun revert(id: String) {
        if (!reverting.add(id)) return
        viewModelScope.launch {
            try {
                runner.runRevert(ai.revert(id))
                queue.send(UiEvent.Snack("Reverted · original entry kept"))
            } finally {
                reverting.remove(id)
            }
        }
    }
}
