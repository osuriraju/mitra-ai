package tech.beyondscale.mitra.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
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
import tech.beyondscale.mitra.data.local.assembleGoals
import tech.beyondscale.mitra.data.local.shareForRepository
import tech.beyondscale.mitra.data.local.writeTo
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.GoalDto
import tech.beyondscale.mitra.data.remote.createBody
import tech.beyondscale.mitra.data.remote.milestoneBody
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncHandler
import tech.beyondscale.mitra.domain.goals.applyChanges
import tech.beyondscale.mitra.domain.goals.applyMilestone
import tech.beyondscale.mitra.domain.goals.applyProgress
import tech.beyondscale.mitra.domain.goals.newGoal
import tech.beyondscale.mitra.domain.goals.withMilestone
import tech.beyondscale.mitra.domain.goals.withoutMilestone
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalChange
import tech.beyondscale.mitra.domain.model.GoalDraft
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.domain.model.MilestoneChange
import tech.beyondscale.mitra.domain.model.MilestoneDraft
import tech.beyondscale.mitra.domain.repository.GoalsRepository

/**
 * Goals, offline-first (the `MoneyRepositoryImpl` skeleton). Every write reads the local goal, applies the server's
 * maths (`GoalMath`) and rewrites the goal with its milestones and progress (`Goal.writeTo`), then queues its row.
 *
 * Every goal-scoped row (goal, progress, milestone) is keyed by the goal id, so "pending" is decided per goal: a server
 * copy (echo or refresh) never overwrites a goal that still has any queued row, never resurrects a deleted goal and
 * therefore never re-adds a milestone whose DELETE is queued. `goalId` links on tasks, projects and habits are never
 * scrubbed (the server keeps them; screens treat an unknown goal as no goal).
 */
class GoalsRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val prefs: PrefStore,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val clock: AppClock,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GoalsRepository {
    /** Bumped before every local write and every applied server echo — see `TasksRepositoryImpl.writes`. */
    private val writes = MutableStateFlow(0L)

    override val goals: Flow<List<Goal>> = combine(
        db.goalsQueries.selectAll().asFlow().mapToList(io),
        db.milestonesQueries.selectAll().asFlow().mapToList(io),
        db.goalProgressQueries.selectAll().asFlow().mapToList(io),
    ) { goals, milestones, progress -> assembleGoals(goals, milestones, progress) }.shareForRepository(scope)

    init {
        // Every goal write answers with the whole goal (DELETE goals/:id answers { ok: true }).
        val handler = object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(response) ?: return
                writes.update { it + 1 }
                withContext(io) {
                    db.transaction {
                        // Deleted locally while the write was in flight, or a newer goal-scoped row still queued: skip.
                        val position = positionOf(dto.id) ?: return@transaction
                        if (dto.id in pendingGoals()) return@transaction
                        dto.toDomain().writeTo(db, position)
                    }
                }
            }
            // e.g. 400 VALIDATION, 404 for a goal deleted on the web, 409 ID_CONFLICT (a reused progress id): the
            // server copy decides.
            override suspend fun onRejected(error: AppError) { refresh() }
        }
        engine.register(GOAL, handler)
        engine.register(GOAL_PROGRESS, handler)
        engine.register(MILESTONE, handler)
    }

    /* ---------- reads ---------- */

    override suspend fun refresh(): Outcome<Unit> {
        repeat(REFRESH_ATTEMPTS) {
            val owner = withContext(io) { prefs.get(PrefKeys.LAST_USER_ID) } ?: return Outcome.Err(AppError.Unauthenticated())
            val seen = writes.value to engine.acknowledged.value
            val body = when (val r = api.call<List<GoalDto>>(HttpMethod.Get, "goals")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val settled = withContext(io) {
                db.transactionWithResult {
                    when {
                        // Signed out or switched account while the request was out: this data belongs to nobody here.
                        prefs.get(PrefKeys.LAST_USER_ID) != owner -> true
                        (writes.value to engine.acknowledged.value) != seen -> false
                        else -> { applyList(body); true }
                    }
                }
            }
            if (settled) return Outcome.Ok(Unit)
        }
        // Still racing writes: applied nothing. ModuleRefresherImpl refreshes once more after the drain that follows.
        return Outcome.Err(AppError.Server(409, GoalsRepository.REFRESH_SUPERSEDED, "Your changes are still syncing. Try again in a moment."))
    }

    /** Replaces every goal without queued rows wholesale; drops goals the server no longer has unless pending. */
    private fun applyList(body: List<GoalDto>) {
        val pending = pendingGoals()
        val server = body.map { it.id }.toSet()
        db.goalsQueries.selectIds().executeAsList().filter { it !in server && it !in pending }.forEach { removeGoal(it) }
        body.forEachIndexed { i, dto -> if (dto.id !in pending) dto.toDomain().writeTo(db, i.toLong()) }
    }

    /* ---------- writes ---------- */

    override suspend fun create(draft: GoalDraft): Goal {
        val goal = newGoal(newId(), draft, clock.today(), clock.now().toString(), ::newId)
        write {
            goal.writeTo(db, db.goalsQueries.nextPosition().executeAsOne())
            outbox.enqueue(GOAL, "POST", "goals", goal.createBody(), entityId = goal.id)
        }
        return goal
    }

    override suspend fun update(id: String, changes: List<GoalChange>) {
        if (changes.isEmpty()) return
        val normalized = changes.normalized()
        write {
            edit(id) { it.applyChanges(normalized) } ?: return@write
            outbox.enqueue(GOAL, "PATCH", "goals/$id", goalPatch(normalized), coalesceKey = "goal:$id", entityId = id)
        }
    }

    override suspend fun delete(id: String) {
        write {
            if (positionOf(id) == null) return@write
            removeGoal(id)
            outbox.enqueue(GOAL, "DELETE", "goals/$id", null, entityId = id)
        }
    }

    override suspend fun logProgress(id: String, value: Double, note: String?) {
        val today = clock.today()
        // zod trims the note and the web sends `note || null`.
        val cleanNote = note?.trim()?.ifEmpty { null }
        write {
            edit(id) { it.applyProgress(value, cleanNote, today) } ?: return@write
            // The id is generated once and stored in the row, so every resend carries it: the server applies a log
            // once and answers a retry with the goal as it stands. The local date beats the server's UTC default.
            val body = buildJsonObject { put("id", newId()); put("value", value); put("note", cleanNote); put("date", today) }
            outbox.enqueue(GOAL_PROGRESS, "POST", "goals/$id/progress", body, entityId = id)
        }
    }

    override suspend fun addMilestone(goalId: String, draft: MilestoneDraft): Milestone? {
        val m = Milestone(newId(), draft.title.trim(), draft.target, draft.targetDate, reachedOn = null, notes = draft.notes)
        var added: Milestone? = null
        write {
            edit(goalId) { it.withMilestone(m) } ?: return@write
            outbox.enqueue(MILESTONE, "POST", "goals/$goalId/milestones", milestoneBody(m), entityId = goalId)
            added = m
        }
        return added
    }

    override suspend fun updateMilestone(goalId: String, id: String, changes: List<MilestoneChange>) {
        if (changes.isEmpty()) return
        val normalized = changes.normalizedMilestone()
        write {
            val goal = selectGoal(goalId) ?: return@write
            if (goal.milestones.none { it.id == id }) return@write
            edit(goalId) { it.applyMilestone(id, normalized) }
            outbox.enqueue(MILESTONE, "PATCH", "goals/$goalId/milestones/$id", milestonePatch(normalized), coalesceKey = "milestone:$id", entityId = goalId)
        }
    }

    override suspend fun deleteMilestone(goalId: String, id: String) {
        write {
            val goal = selectGoal(goalId) ?: return@write
            if (goal.milestones.none { it.id == id }) return@write
            edit(goalId) { it.withoutMilestone(id) }
            outbox.enqueue(MILESTONE, "DELETE", "goals/$goalId/milestones/$id", null, entityId = goalId)
        }
    }

    /* ---------- helpers ---------- */

    /** Local change + outbox row in one transaction. The counter is bumped first (see [writes]). */
    private suspend fun write(block: () -> Unit) {
        writes.update { it + 1 }
        withContext(io) { db.transaction { block() } }
        engine.requestSync()
    }

    private fun positionOf(id: String): Long? = db.goalsQueries.selectById(id).executeAsOneOrNull()?.position

    /** The local goal with its milestones and progress, null when unknown. Call inside a transaction. */
    private fun selectGoal(id: String): Goal? {
        val row = db.goalsQueries.selectById(id).executeAsOneOrNull() ?: return null
        // goal_progress has no per-goal query (Task 4's schema); a goal's log is short and this runs once per write.
        val progress = db.goalProgressQueries.selectAll().executeAsList().filter { it.goalId == id }
        return assembleGoals(listOf(row), db.milestonesQueries.selectForGoal(id).executeAsList(), progress).single()
    }

    /** Rewrites the goal (row, milestones, progress positions) after [f], keeping its position; null when unknown. */
    private fun edit(id: String, f: (Goal) -> Goal): Goal? {
        val position = positionOf(id) ?: return null
        val goal = selectGoal(id) ?: return null
        return f(goal).also { it.writeTo(db, position) }
    }

    /** The goal and its children: the tables have no foreign keys, so nothing cascades. Call inside a transaction. */
    private fun removeGoal(id: String) {
        db.goalsQueries.delete(id)
        db.milestonesQueries.deleteForGoal(id)
        db.goalProgressQueries.deleteForGoal(id)
    }

    /** Goals with any queued goal-scoped row (all three entities use `entityId = goalId`). */
    private fun pendingGoals(): Set<String> = (pendingIds(GOAL) + pendingIds(GOAL_PROGRESS) + pendingIds(MILESTONE))

    private fun pendingIds(entity: String): Set<String> =
        db.outboxQueries.pendingEntityIds(entity).executeAsList().filter { it.isNotEmpty() }.toSet()

    private fun decode(element: JsonElement?): GoalDto? =
        (element as? JsonObject)?.let { runCatching { api.json.decodeFromJsonElement(GoalDto.serializer(), it) }.getOrNull() }

    companion object {
        const val GOAL = OutboxEntities.GOAL
        const val GOAL_PROGRESS = OutboxEntities.GOAL_PROGRESS
        const val MILESTONE = OutboxEntities.MILESTONE
        private const val REFRESH_ATTEMPTS = 3
    }
}
