package tech.beyondscale.mitra.domain.tasks

import kotlinx.coroutines.flow.first
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.repository.TasksRepository

/** What a complete / reopen did — the caller turns [Completed] into the `Completed · <title>` snackbar with Undo. */
sealed interface Completion {
    data object NotFound : Completion
    /** [task] is the snapshot before reopening. */
    data class Reopened(val task: Task) : Completion
    /** [task] is the snapshot before completing; [spawnedId] is the queued next occurrence of a recurring task. */
    data class Completed(val task: Task, val spawnedId: String?) : Completion
}

private suspend fun TasksRepository.find(id: String): Task? = tasks.first().firstOrNull { it.id == id }

/** A task row's check: reopens a done task, completes an open one. */
suspend fun TasksRepository.toggleCompletion(id: String): Completion {
    val t = find(id) ?: return Completion.NotFound
    if (t.done) {
        setDone(id, false)
        return Completion.Reopened(t)
    }
    return Completion.Completed(t, setDone(id, true))
}

/** Completes without ever reopening; an already-done task is reported as completed with nothing spawned. */
suspend fun TasksRepository.complete(id: String): Completion {
    val t = find(id) ?: return Completion.NotFound
    if (t.done) return Completion.Completed(t, null)
    return Completion.Completed(t, setDone(id, true))
}

/** Undo for a completion: reopens the task and deletes the recurring occurrence it spawned. */
suspend fun TasksRepository.undoCompletion(id: String, spawnedId: String?) {
    setDone(id, false)
    spawnedId?.let { delete(it) }
}
