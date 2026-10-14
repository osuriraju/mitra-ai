package tech.beyondscale.mitra.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import tech.beyondscale.mitra.designsystem.components.LocalSnackbar

enum class HapticKind { Confirm, Reject, LongPress }

/** One-off effects from a ViewModel. [A] is the action an Undo snackbar replays. */
sealed interface UiEvent<out A> {
    data class Snack<out A>(val text: String, val undo: A? = null) : UiEvent<A>
    data class Haptic(val kind: HapticKind) : UiEvent<Nothing>
    data object Close : UiEvent<Nothing>
}

class EventQueue<A> {
    private val channel = Channel<UiEvent<A>>(Channel.BUFFERED)
    val events: Flow<UiEvent<A>> = channel.receiveAsFlow()
    fun send(e: UiEvent<A>) {
        channel.trySend(e)
    }
}

@Composable
fun <A> EventEffect(events: Flow<UiEvent<A>>, onAction: (A) -> Unit, onClose: () -> Unit = {}) {
    val snackbar = LocalSnackbar.current
    val haptics = LocalHapticFeedback.current
    val action by rememberUpdatedState(onAction)
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(events) {
        events.collect { e ->
            when (e) {
                is UiEvent.Snack -> {
                    val undo = e.undo
                    // The shell's scope hosts the snackbar, so Undo survives this screen leaving composition.
                    if (undo != null) snackbar.show(e.text, "Undo") { action(undo) } else snackbar.show(e.text)
                }
                is UiEvent.Haptic -> haptics.performHapticFeedback(
                    when (e.kind) {
                        HapticKind.Confirm -> HapticFeedbackType.Confirm
                        HapticKind.Reject -> HapticFeedbackType.Reject
                        HapticKind.LongPress -> HapticFeedbackType.LongPress
                    },
                )
                UiEvent.Close -> close()
            }
        }
    }
}
