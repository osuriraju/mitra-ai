package tech.beyondscale.mitra.feature.common

import tech.beyondscale.mitra.domain.tasks.Completion

/** What an Undo of a completion needs: the task to reopen and the recurring occurrence to delete. */
data class CompletionUndo(val id: String, val spawnedId: String?)

/**
 * The shared completion feedback: [Completion.Completed] → a confirm haptic, then `Completed · <title>` with Undo
 * (the screen's own action built by [undo]). Reopened / not found → nothing; callers that confirm a reopen do it themselves.
 */
fun <A> EventQueue<A>.announce(c: Completion, undo: (CompletionUndo) -> A) {
    if (c !is Completion.Completed) return
    send(UiEvent.Haptic(HapticKind.Confirm))
    send(UiEvent.Snack("Completed · ${c.task.title}", undo(CompletionUndo(c.task.id, c.spawnedId))))
}
