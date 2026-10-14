package tech.beyondscale.mitra.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.newId
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.ProjectDto
import tech.beyondscale.mitra.data.remote.TaskDto
import tech.beyondscale.mitra.data.remote.TasksBootstrapDto
import tech.beyondscale.mitra.data.remote.createBody
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncHandler
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.model.TaskStatus
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.nextDue

class TasksRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val prefs: PrefStore,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val clock: AppClock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TasksRepository {
    /**
     * Bumped before every local write and every applied server echo. A refresh snapshots this and
     * [SyncEngine.acknowledged] before its GET and discards its result if either moved: the snapshot may predate a
     * change that has since been written locally or acknowledged by the server.
     */
    private val writes = MutableStateFlow(0L)

    override val tasks: Flow<List<Task>> =
        db.tasksQueries.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }
    override val projects: Flow<List<Project>> =
        db.projectsQueries.selectAll().asFlow().mapToList(io).map { rows -> rows.map { it.toDomain() } }

    init {
        engine.register(TASK, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(TaskDto.serializer(), response) ?: return // DELETE answers { ok: true }
                applyEcho(TASK, dto.id) {
                    // Deleted locally while the write was in flight: don't resurrect it.
                    if (db.tasksQueries.selectById(dto.id).executeAsOneOrNull() != null) db.tasksQueries.upsert(dto.toDomain().toRow())
                }
            }
            override suspend fun onRejected(error: AppError) { refresh() }
        })
        engine.register(PROJECT, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(ProjectDto.serializer(), response) ?: return
                applyEcho(PROJECT, dto.id) {
                    if (db.projectsQueries.selectById(dto.id).executeAsOneOrNull() != null) db.projectsQueries.upsert(dto.toDomain().toRow())
                }
            }
            override suspend fun onRejected(error: AppError) { refresh() }
        })
    }

    /* ---------- reads ---------- */

    override suspend fun refresh(): Outcome<Unit> {
        repeat(REFRESH_ATTEMPTS) {
            val owner = withContext(io) { prefs.get(PrefKeys.LAST_USER_ID) } ?: return Outcome.Err(AppError.Unauthenticated())
            val seen = writes.value to engine.acknowledged.value
            val body = when (val r = api.call<TasksBootstrapDto>(HttpMethod.Get, "tasks/bootstrap")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val settled = withContext(io) {
                db.transactionWithResult {
                    when {
                        // Signed out or switched account while the request was out: this data belongs to nobody here.
                        prefs.get(PrefKeys.LAST_USER_ID) != owner -> true
                        (writes.value to engine.acknowledged.value) != seen -> false
                        else -> { applyBootstrap(body); true }
                    }
                }
            }
            if (settled) return Outcome.Ok(Unit)
        }
        // Still racing writes; the refresh after the queue drains will reconcile.
        return Outcome.Ok(Unit)
    }

    /** Upsert everything the server has, delete what it no longer has — except records with queued changes. */
    private fun applyBootstrap(body: TasksBootstrapDto) {
        val pendingTasks = pendingIds(TASK)
        val pendingProjects = pendingIds(PROJECT)
        val localTasks = db.tasksQueries.selectIds().executeAsList().toSet()
        // Subtasks of a task whose DELETE is still queued: the server keeps them until that delete lands.
        val incoming = body.tasks.filterNot { it.parentId != null && it.parentId in pendingTasks && it.parentId !in localTasks }
        val serverTaskIds = incoming.map { it.id }.toSet()
        localTasks.filter { it !in serverTaskIds && it !in pendingTasks }.forEach { db.tasksQueries.delete(it) }
        incoming.filter { it.id !in pendingTasks }.forEach { db.tasksQueries.upsert(it.toDomain().toRow()) }

        val serverProjectIds = body.projects.map { it.id }.toSet()
        db.projectsQueries.selectIds().executeAsList().filter { it !in serverProjectIds && it !in pendingProjects }.forEach { db.projectsQueries.delete(it) }
        body.projects.filter { it.id !in pendingProjects }.forEach { db.projectsQueries.upsert(it.toDomain().toRow()) }
    }

    /* ---------- task writes ---------- */

    override suspend fun create(draft: TaskDraft): Task {
        val today = clock.today()
        val done = draft.status == TaskStatus.Done
        val task = Task(
            id = newId(), title = draft.title.trim(), projectId = draft.projectId, goalId = draft.goalId, due = draft.due,
            time = draft.time, estMin = draft.estMin, pri = draft.pri, status = draft.status, done = done,
            completedAt = if (done) today else null, parentId = draft.parentId, notes = draft.notes,
            recurring = draft.recurring, someday = draft.someday, createdAt = clock.now().toString(),
        )
        write { insertTask(task) }
        return task
    }

    override suspend fun update(id: String, changes: List<TaskChange>) {
        if (changes.isEmpty()) return
        val today = clock.today()
        write {
            val old = selectTask(id) ?: return@write
            val (updated, patch) = applyChanges(old, changes, today)
            db.tasksQueries.upsert(updated.toRow())
            patchTask(id, patch)
        }
    }

    override suspend fun setDone(id: String, done: Boolean): String? {
        val today = clock.today()
        var spawned: String? = null
        write {
            val old = selectTask(id) ?: return@write
            if (old.done == done) return@write
            val (updated, patch) = completion(old, done, today)
            db.tasksQueries.upsert(updated.toRow())
            patchTask(id, patch)
            val rule = old.recurring
            if (done && rule != null) {
                val all = db.tasksQueries.selectAll().executeAsList().map { it.toDomain() }
                // Same guard as the server's toggle: never two open occurrences of one series.
                if (all.none { it.id != id && !it.done && it.title == old.title && it.recurring == rule }) {
                    val next = old.copy(
                        id = newId(), due = nextDue(old.due, rule, today), status = TaskStatus.Todo, done = false,
                        completedAt = null, someday = false, source = "recurring", createdAt = clock.now().toString(),
                    )
                    insertTask(next)
                    spawned = next.id
                }
            }
        }
        return spawned
    }

    override suspend fun delete(id: String): List<Task> {
        var removed = emptyList<Task>()
        write {
            val target = selectTask(id) ?: return@write
            removed = listOf(target) + db.tasksQueries.selectAll().executeAsList().map { it.toDomain() }.filter { it.parentId == id }
            removed.forEach { db.tasksQueries.delete(it.id) }
            outbox.enqueue(TASK, "DELETE", "tasks/$id", null, entityId = id) // the server cascades to subtasks
        }
        return removed
    }

    override suspend fun restore(removed: List<Task>) {
        if (removed.isEmpty()) return
        write { removed.sortedBy { it.parentId != null }.forEach { insertTask(it) } }
    }

    /* ---------- project writes ---------- */

    override suspend fun createProject(name: String, emoji: String): Project {
        val p = Project(newId(), name.trim(), emoji, null, clock.now().toString())
        write {
            db.projectsQueries.upsert(p.toRow())
            outbox.enqueue(PROJECT, "POST", "projects", buildJsonObject { put("id", p.id); put("name", p.name); put("emoji", p.emoji) }, entityId = p.id)
        }
        return p
    }

    override suspend fun updateProject(id: String, name: String, emoji: String) {
        write {
            val old = db.projectsQueries.selectById(id).executeAsOneOrNull()?.toDomain() ?: return@write
            db.projectsQueries.upsert(old.copy(name = name.trim(), emoji = emoji).toRow())
            outbox.enqueue(
                PROJECT, "PATCH", "projects/$id", buildJsonObject { put("name", name.trim()); put("emoji", emoji) },
                coalesceKey = "project:$id", entityId = id,
            )
        }
    }

    override suspend fun deleteProject(id: String) {
        write {
            db.projectsQueries.delete(id)
            db.tasksQueries.clearProject(id)
            outbox.enqueue(PROJECT, "DELETE", "projects/$id", null, entityId = id)
        }
    }

    /* ---------- helpers ---------- */

    /** Local change + outbox row in one transaction. The counter is bumped first (see [writes]). */
    private suspend fun write(block: () -> Unit) {
        writes.update { it + 1 }
        withContext(io) { db.transaction { block() } }
        engine.requestSync()
    }

    private fun selectTask(id: String): Task? = db.tasksQueries.selectById(id).executeAsOneOrNull()?.toDomain()

    private fun insertTask(t: Task) {
        db.tasksQueries.upsert(t.toRow())
        outbox.enqueue(TASK, "POST", "tasks", t.createBody(), entityId = t.id)
    }

    private fun patchTask(id: String, patch: JsonObject) =
        outbox.enqueue(TASK, "PATCH", "tasks/$id", patch, coalesceKey = "task:$id", entityId = id)

    private fun pendingIds(entity: String): Set<String> =
        db.outboxQueries.pendingEntityIds(entity).executeAsList().filter { it.isNotEmpty() }.toSet()

    private fun <T> decode(serializer: KSerializer<T>, element: JsonElement?): T? =
        (element as? JsonObject)?.let { runCatching { api.json.decodeFromJsonElement(serializer, it) }.getOrNull() }

    /** Server copy of one record after its write was acknowledged — unless a newer local change to it is still queued. */
    private suspend fun applyEcho(entity: String, id: String, apply: () -> Unit) {
        writes.update { it + 1 }
        withContext(io) { db.transaction { if (id !in pendingIds(entity)) apply() } }
    }

    companion object {
        const val TASK = OutboxEntities.TASK
        const val PROJECT = OutboxEntities.PROJECT
        private const val REFRESH_ATTEMPTS = 3
    }
}
