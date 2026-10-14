package tech.beyondscale.mitra.feature.common

import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.Completion
import tech.beyondscale.mitra.domain.tasks.toggleCompletion

/** What an Undo of a completion needs: the task to reopen and the recurring occurrence to delete. */
data class CompletionUndo(val id: String, val spawnedId: String?)

/**
 * A task row's check with the shared feedback: completing sends a confirm haptic **before** the write (instant tap
 * feedback), then [announce]s; reopening or an unknown id sends nothing. Returns what happened so a caller can add
 * its own reopen feedback.
 */
suspend fun <A> EventQueue<A>.toggleTask(tasks: TasksRepository, id: String, undo: (CompletionUndo) -> A): Completion {
    val c = tasks.toggleCompletion(id) { send(UiEvent.Haptic(HapticKind.Confirm)) }
    announce(c, undo)
    return c
}

/**
 * The completion snackbar: [Completion.Completed] → `Completed · <title>` with Undo (the screen's own action built by
 * [undo]). Reopened / not found → nothing. The haptic is not sent here — it belongs before the write ([toggleTask]).
 */
fun <A> EventQueue<A>.announce(c: Completion, undo: (CompletionUndo) -> A) {
    if (c !is Completion.Completed) return
    send(UiEvent.Snack("Completed · ${c.task.title}", undo(CompletionUndo(c.task.id, c.spawnedId))))
}
