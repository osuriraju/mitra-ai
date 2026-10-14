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
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.newId
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.data.db.Habit_logs
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.db.Outbox
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.shareForRepository
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.HabitDto
import tech.beyondscale.mitra.data.remote.createBody
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncHandler
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitChange
import tech.beyondscale.mitra.domain.model.HabitDraft
import tech.beyondscale.mitra.domain.model.applyChanges
import tech.beyondscale.mitra.domain.model.toHabit
import tech.beyondscale.mitra.domain.repository.HabitsRepository

/**
 * Habits and their daily logs, offline-first (the `MoneyRepositoryImpl` skeleton). Every outbox row of a habit —
 * its POST / PATCH / DELETE (`habit`) and its log PUTs (`habit-log`) — carries `entityId = habitId`, so a habit is
 * "pending" while any of them is queued: no echo or refresh overwrites it, and none resurrects it once deleted.
 */
class HabitsRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val prefs: PrefStore,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val clock: AppClock,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HabitsRepository {
    /** Bumped before every local write and every applied server echo — see `TasksRepositoryImpl.writes`. */
    private val writes = MutableStateFlow(0L)

    override val habits: Flow<List<Habit>> = combine(
        db.habitsQueries.selectAll().asFlow().mapToList(io),
        db.habitLogsQueries.selectAll().asFlow().mapToList(io),
    ) { rows, logRows ->
        val logs = logRows.groupBy { it.habitId }.mapValues { (_, l) -> l.associate { it.date to it.value_.toInt() } }
        rows.map { it.toDomain(logs[it.id].orEmpty()) }
    }.shareForRepository(scope)

    init {
        val handler = object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = decode(response) ?: return // DELETE answers { ok: true }
                val cutoff = logWindowStart()
                writes.update { it + 1 }
                withContext(io) { db.transaction { echo(dto, cutoff) } }
            }
            // e.g. 404 NOT_FOUND (deleted on the web) or a 400 the form let through: the server copy decides.
            override suspend fun onRejected(error: AppError) { refresh() }
            override suspend fun onRejected(error: AppError, row: Outbox) { refresh() }
        }
        engine.register(HABIT, handler)
        engine.register(HABIT_LOG, handler)
    }

    /* ---------- reads ---------- */

    override suspend fun refresh(): Outcome<Unit> {
        repeat(REFRESH_ATTEMPTS) {
            val owner = withContext(io) { prefs.get(PrefKeys.LAST_USER_ID) } ?: return Outcome.Err(AppError.Unauthenticated())
            val seen = writes.value to engine.acknowledged.value
            val body = when (val r = api.call<List<HabitDto>>(HttpMethod.Get, "habits")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val cutoff = logWindowStart()
            val settled = withContext(io) {
                db.transactionWithResult {
                    when {
                        // Signed out or switched account while the request was out: this data belongs to nobody here.
                        prefs.get(PrefKeys.LAST_USER_ID) != owner -> true
                        (writes.value to engine.acknowledged.value) != seen -> false
                        else -> { applyList(body, cutoff); true }
                    }
                }
            }
            if (settled) return Outcome.Ok(Unit)
        }
        // Still racing writes: applied nothing. ModuleRefresherImpl refreshes once more after the drain that follows.
        return Outcome.Err(AppError.Server(409, HabitsRepository.REFRESH_SUPERSEDED, "Your changes are still syncing. Try again in a moment."))
    }

    /** Server list → local tables, except habits with queued rows (kept as they are, or kept deleted). */
    private fun applyList(body: List<HabitDto>, cutoff: String) {
        val pending = pendingHabitIds()
        val server = body.map { it.id }.toSet()
        db.habitsQueries.selectIds().executeAsList().filter { it !in server && it !in pending }.forEach { deleteLocally(it) }
        body.forEachIndexed { i, dto ->
            if (dto.id !in pending) replace(dto, i.toLong(), cutoff)
        }
    }

    /* ---------- writes ---------- */

    override suspend fun create(draft: HabitDraft): Habit {
        val h = draft.toHabit(newId(), clock.now().toString())
        write {
            db.habitsQueries.upsert(h.toRow(db.habitsQueries.nextPosition().executeAsOne()))
            outbox.enqueue(HABIT, "POST", "habits", h.createBody(), entityId = h.id)
        }
        return h
    }

    override suspend fun update(id: String, changes: List<HabitChange>) {
        if (changes.isEmpty()) return
        val clean = normalized(changes)
        write {
            val row = db.habitsQueries.selectById(id).executeAsOneOrNull() ?: return@write
            db.habitsQueries.upsert(row.toDomain(emptyMap()).applyChanges(clean).toRow(row.position))
            outbox.enqueue(HABIT, "PATCH", "habits/$id", habitPatch(clean), coalesceKey = "habit:$id", entityId = id)
        }
    }

    override suspend fun delete(id: String) {
        write {
            if (db.habitsQueries.selectById(id).executeAsOneOrNull() == null) return@write
            deleteLocally(id)
            outbox.enqueue(HABIT, "DELETE", "habits/$id", null, entityId = id)
        }
    }

    override suspend fun setLog(id: String, date: String, value: Int?) {
        val v = value?.takeIf { it != 0 } // the server deletes the log for null and for 0
        write {
            if (db.habitsQueries.selectById(id).executeAsOneOrNull() == null) return@write
            if (v == null) db.habitLogsQueries.deleteOne(id, date) else db.habitLogsQueries.upsert(Habit_logs(id, date, v.toLong()))
            val body = buildJsonObject { put("date", date); put("value", v) }
            outbox.enqueue(HABIT_LOG, "PUT", "habits/$id/logs", body, coalesceKey = "habit-log:$id:$date", entityId = id)
        }
    }

    /* ---------- helpers ---------- */

    /** Local change + outbox row in one transaction. The counter is bumped first (see [writes]). */
    private suspend fun write(block: () -> Unit) {
        writes.update { it + 1 }
        withContext(io) { db.transaction { block() } }
        engine.requestSync()
    }

    /** Server copy after an acknowledged write: only for a habit still here with nothing else queued. In a transaction. */
    private fun echo(dto: HabitDto, cutoff: String) {
        val local = db.habitsQueries.selectById(dto.id).executeAsOneOrNull() ?: return // deleted locally: no resurrection
        if (dto.id in pendingHabitIds()) return // a newer local change (edit, another day's log) is still queued
        replace(dto, local.position, cutoff)
    }

    /**
     * Replaces a habit row and its logs inside the server's window. The server only sends the last 400 days (by its
     * UTC date), so logs older than [cutoff] are never deleted for being absent. In a transaction.
     */
    private fun replace(dto: HabitDto, position: Long, cutoff: String) {
        val h = dto.toDomain()
        db.habitsQueries.upsert(h.toRow(position))
        db.habitLogsQueries.deleteForHabitOnOrAfter(h.id, cutoff)
        h.logs.forEach { (date, value) -> db.habitLogsQueries.upsert(Habit_logs(h.id, date, value.toLong())) }
    }

    /** There is no SQL cascade: a habit's logs go with it. In a transaction. */
    private fun deleteLocally(id: String) {
        db.habitsQueries.delete(id)
        db.habitLogsQueries.deleteForHabit(id)
    }

    /** First local day the server's answer is known to be complete for (400 days, minus a time-zone margin). */
    private fun logWindowStart(): String = addDays(clock.today(), -LOG_WINDOW_DAYS)

    private fun pendingHabitIds(): Set<String> = pendingIds(HABIT) + pendingIds(HABIT_LOG)

    private fun pendingIds(entity: String): Set<String> =
        db.outboxQueries.pendingEntityIds(entity).executeAsList().filter { it.isNotEmpty() }.toSet()

    private fun decode(element: JsonElement?): HabitDto? =
        (element as? JsonObject)?.let { runCatching { api.json.decodeFromJsonElement(HabitDto.serializer(), it) }.getOrNull() }

    companion object {
        const val HABIT = OutboxEntities.HABIT
        const val HABIT_LOG = OutboxEntities.HABIT_LOG
        private const val REFRESH_ATTEMPTS = 3
        /** The server keeps 400 days by its UTC date; two days less covers the device's local day. */
        private const val LOG_WINDOW_DAYS = 398
    }
}
