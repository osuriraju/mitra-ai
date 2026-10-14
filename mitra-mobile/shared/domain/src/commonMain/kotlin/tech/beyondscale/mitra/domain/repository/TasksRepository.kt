package tech.beyondscale.mitra.domain.repository

import kotlinx.coroutines.flow.Flow
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft

/** Offline-first: every write lands in the local DB immediately and reaches the server through the outbox. */
interface TasksRepository {
    /** All tasks on the device, newest first (server order). */
    val tasks: Flow<List<Task>>
    val projects: Flow<List<Project>>

    /**
     * Re-reads tasks + projects from the server; local records with queued changes are kept. If local writes or
     * server acknowledgements keep overtaking the snapshot, nothing is applied and the result is
     * `Err(AppError.Server(code = REFRESH_SUPERSEDED))`; the module refresher reconciles after the queue drains.
     */
    suspend fun refresh(): Outcome<Unit>
    suspend fun create(draft: TaskDraft): Task
    suspend fun update(id: String, changes: List<TaskChange>)
    /** Completes or reopens; a no-op if already in that state. Completing a recurring task queues its next occurrence and returns its id. */
    suspend fun setDone(id: String, done: Boolean): String?
    /** Deletes the task and its subtasks; returns what was removed so the caller can offer Undo. */
    suspend fun delete(id: String): List<Task>
    /** Undo for [delete]: re-creates the tasks with their original ids. */
    suspend fun restore(removed: List<Task>)
    suspend fun createProject(name: String, emoji: String): Project
    suspend fun updateProject(id: String, name: String, emoji: String)
    /** Deletes the project; its tasks move to the Inbox (as on the server). */
    suspend fun deleteProject(id: String)

    companion object {
        /** [AppError.Server.code][tech.beyondscale.mitra.core.AppError.Server.code] of a refresh that applied nothing (see [refresh]). */
        const val REFRESH_SUPERSEDED = "REFRESH_SUPERSEDED"
    }
}
