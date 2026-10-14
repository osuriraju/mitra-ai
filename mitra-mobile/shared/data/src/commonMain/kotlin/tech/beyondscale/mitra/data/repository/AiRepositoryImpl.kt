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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.newId
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.shareForRepository
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.AiActionDto
import tech.beyondscale.mitra.data.remote.AiReplyDto
import tech.beyondscale.mitra.data.remote.AiStatusDto
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.JournalDraftDto
import tech.beyondscale.mitra.data.remote.SuggestionDto
import tech.beyondscale.mitra.data.remote.createBody
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.sync.SyncHandler
import tech.beyondscale.mitra.domain.ai.askHistory
import tech.beyondscale.mitra.domain.model.AiAction
import tech.beyondscale.mitra.domain.model.AiActionDraft
import tech.beyondscale.mitra.domain.model.AiMessage
import tech.beyondscale.mitra.domain.model.AiWho
import tech.beyondscale.mitra.domain.model.JournalDraft
import tech.beyondscale.mitra.domain.model.RevertOp
import tech.beyondscale.mitra.domain.model.Suggestion
import tech.beyondscale.mitra.domain.model.SuggestionState
import tech.beyondscale.mitra.domain.repository.AiRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository

/**
 * The assistant. Chat (`ai/ask`), suggestions and journal drafts are online-only; the conversation and suggestion
 * states are stored on the device only (the server keeps neither). The audit log is offline-first:
 *
 * - [logAction] stores the row under a client UUID and queues `POST ai/actions` with that same `id`, so the server
 *   row has the local id (a retry answers with the original row; a reused id is `409 ID_CONFLICT` → rejected).
 * - [revert] marks the row locally and queues `POST ai/actions/:id/revert` behind the create (FIFO outbox), so a
 *   revert of a never-synced row needs no id swap. The local "Reverted" row is a placeholder until the next refresh
 *   brings the server's own.
 * - While any AI log row is queued, [refresh] keeps the local log as is (only status is updated).
 */
class AiRepositoryImpl(
    private val api: ApiClient,
    private val db: MitraDb,
    private val prefs: PrefStore,
    private val outbox: OutboxWriter,
    private val engine: SyncEngine,
    private val clock: AppClock,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AiRepository {
    /** Bumped before every local log write and applied echo — see `TasksRepositoryImpl.writes`. */
    private val writes = MutableStateFlow(0L)

    override val enabled: Flow<Boolean> = prefs.observe(AI_ENABLED).map { it != "false" }.shareForRepository(scope)

    override val messages: Flow<List<AiMessage>> =
        db.aiMessagesQueries.selectAll().asFlow().mapToList(io).map { l -> l.map { it.toDomain() } }.shareForRepository(scope)

    override val actions: Flow<List<AiAction>> =
        db.aiActionsQueries.selectAll().asFlow().mapToList(io).map { l -> l.map { it.toDomain() } }.shareForRepository(scope)

    override val suggestions: Flow<List<Suggestion>> =
        db.suggestionsQueries.selectAll().asFlow().mapToList(io).map { l -> l.map { it.toDomain() } }.shareForRepository(scope)

    init {
        engine.register(AI_ACTION, object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) {
                val dto = (response as? JsonObject)?.let {
                    runCatching { api.json.decodeFromJsonElement(AiActionDto.serializer(), it) }.getOrNull()
                } ?: return
                writes.update { it + 1 }
                withContext(io) {
                    db.transaction {
                        // Unknown (signed out) or a revert of it still queued: the local copy is newer.
                        if (db.aiActionsQueries.selectById(dto.id).executeAsOneOrNull() == null) return@transaction
                        if (dto.id in pendingIds(AI_REVERT)) return@transaction
                        db.aiActionsQueries.upsert(dto.toDomain().toRow())
                    }
                }
            }
            override suspend fun onRejected(error: AppError) { refresh() }
        })
        engine.register(AI_REVERT, object : SyncHandler {
            // Answers `{ original, reverted, revert }`. The ops were already run once when the user tapped Revert; the
            // response ops (repeated on a retry) are ignored. Re-read so the server's "Reverted" row replaces the placeholder.
            override suspend fun onSuccess(response: JsonElement?) { refresh() }
            override suspend fun onRejected(error: AppError) { refresh() }
        })
    }

    /* ---------- audit log + status ---------- */

    override suspend fun refresh(): Outcome<Unit> {
        repeat(REFRESH_ATTEMPTS) {
            val owner = withContext(io) { prefs.get(PrefKeys.LAST_USER_ID) } ?: return Outcome.Err(AppError.Unauthenticated())
            val seen = writes.value to engine.acknowledged.value
            val status = api.call<AiStatusDto>(HttpMethod.Get, "ai/status")
            val body = when (val r = api.call<List<AiActionDto>>(HttpMethod.Get, "ai/actions")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val settled = withContext(io) {
                db.transactionWithResult {
                    when {
                        prefs.get(PrefKeys.LAST_USER_ID) != owner -> true
                        (writes.value to engine.acknowledged.value) != seen -> false
                        else -> {
                            if (status is Outcome.Ok) prefs.put(AI_ENABLED, status.value.enabled.toString())
                            if (pendingIds(AI_ACTION).isEmpty() && pendingIds(AI_REVERT).isEmpty()) {
                                db.aiActionsQueries.deleteAll()
                                body.forEach { db.aiActionsQueries.upsert(it.toDomain().toRow()) }
                            }
                            true
                        }
                    }
                }
            }
            if (settled) return Outcome.Ok(Unit)
        }
        return Outcome.Err(AppError.Server(409, TasksRepository.REFRESH_SUPERSEDED, "Your changes are still syncing. Try again in a moment."))
    }

    override suspend fun logAction(draft: AiActionDraft): AiAction {
        val action = AiAction(
            id = newId(), at = clock.now().toString(), action = draft.action, detail = draft.detail, state = draft.state,
            tone = draft.tone, revertible = draft.revertible, reverted = false, revert = draft.revert,
        )
        write {
            db.aiActionsQueries.upsert(action.toRow())
            outbox.enqueue(AI_ACTION, "POST", "ai/actions", draft.createBody(action.id), entityId = action.id)
        }
        return action
    }

    override suspend fun revert(id: String): List<RevertOp> {
        var ops: List<RevertOp> = emptyList()
        write {
            val a = db.aiActionsQueries.selectById(id).executeAsOneOrNull()?.toDomain() ?: return@write
            if (a.reverted) return@write
            db.aiActionsQueries.upsert(a.copy(reverted = true, state = "${a.state} · reverted").toRow())
            db.aiActionsQueries.upsert(
                AiAction(newId(), clock.now().toString(), "Reverted", a.detail, "By you", "warning", revertible = false).toRow(),
            )
            outbox.enqueue(AI_REVERT, "POST", "ai/actions/$id/revert", null, entityId = id)
            ops = a.revert
        }
        return ops
    }

    /* ---------- chat (device-local history) ---------- */

    override suspend fun ask(text: String): Outcome<AiMessage> {
        val input = text.trim()
        val userAt = nextStamp()
        val user = AiMessage(newId(), AiWho.User, input, createdAt = userAt)
        val history = withContext(io) {
            db.aiMessagesQueries.upsert(user.toRow())
            askHistory(db.aiMessagesQueries.selectAll().executeAsList().map { it.toDomain() })
        }
        val body = buildJsonObject {
            put("text", input)
            put("history", JsonArray(history.map { (who, t) -> buildJsonObject { put("who", who); put("text", t) } }))
        }
        val result = api.call<AiReplyDto>(HttpMethod.Post, "ai/ask", body)
        val reply = when (result) {
            is Outcome.Ok -> result.value.toDomain().let { AiMessage(newId(), AiWho.Ai, it.text, it.chart, it.proposal, createdAt = nextStamp()) }
            is Outcome.Err -> AiMessage(newId(), AiWho.Ai, "Sorry — ${result.error.message}", createdAt = nextStamp())
        }
        withContext(io) { db.aiMessagesQueries.upsert(reply.toRow()) }
        return when (result) {
            is Outcome.Ok -> Outcome.Ok(reply)
            is Outcome.Err -> result
        }
    }

    override suspend fun setApplied(messageId: String, applied: Boolean) = editMessage(messageId) { it.copy(applied = applied) }

    override suspend fun cancelProposal(messageId: String) =
        editMessage(messageId) { if (it.proposal == null) it else it.copy(proposal = null, text = it.text + " (cancelled)") }

    override suspend fun clearConversation() { withContext(io) { db.aiMessagesQueries.deleteAll() } }

    private suspend fun editMessage(id: String, f: (AiMessage) -> AiMessage) {
        withContext(io) {
            db.transaction {
                val m = db.aiMessagesQueries.selectById(id).executeAsOneOrNull()?.toDomain() ?: return@transaction
                db.aiMessagesQueries.upsert(f(m).toRow())
            }
        }
    }

    /** Epoch millis strictly after the newest message, so a fast reply never sorts before its question. */
    private var lastStamp = 0L
    private fun nextStamp(): Long {
        val now = clock.now().toEpochMilliseconds()
        lastStamp = if (now > lastStamp) now else lastStamp + 1
        return lastStamp
    }

    /* ---------- suggestions + journal ---------- */

    override suspend fun loadSuggestions(refresh: Boolean): Outcome<Unit> {
        val path = if (refresh) "ai/suggestions?refresh=1" else "ai/suggestions"
        val fresh = when (val r = api.call<List<SuggestionDto>>(HttpMethod.Get, path)) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
        withContext(io) {
            db.transaction {
                val known = db.suggestionsQueries.selectAll().executeAsList().associate { it.id to it.state }
                db.suggestionsQueries.deleteAll()
                fresh.forEachIndexed { i, dto ->
                    val state = SuggestionState.fromWire(known[dto.id])
                    db.suggestionsQueries.upsert(dto.toDomain().copy(state = state).toRow(i.toLong()))
                }
            }
        }
        return Outcome.Ok(Unit)
    }

    override suspend fun setSuggestionState(id: String, state: SuggestionState) {
        withContext(io) { db.suggestionsQueries.setState(state.wire, id) }
    }

    override suspend fun journalDraft(date: String): Outcome<JournalDraft> =
        when (val r = api.call<JournalDraftDto>(HttpMethod.Post, "ai/journal-draft", buildJsonObject { put("date", JsonPrimitive(date)) })) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok(r.value.toDomain())
        }

    /* ---------- helpers ---------- */

    private suspend fun write(block: () -> Unit) {
        writes.update { it + 1 }
        withContext(io) { db.transaction { block() } }
        engine.requestSync()
    }

    private fun pendingIds(entity: String): Set<String> =
        db.outboxQueries.pendingEntityIds(entity).executeAsList().filter { it.isNotEmpty() }.toSet()

    companion object {
        const val AI_ACTION = OutboxEntities.AI_ACTION
        const val AI_REVERT = OutboxEntities.AI_REVERT
        /** Pref key: last `ai/status.enabled` ("true"/"false"); absent = enabled. */
        const val AI_ENABLED = "aiEnabled"
        private const val REFRESH_ATTEMPTS = 3
    }
}
