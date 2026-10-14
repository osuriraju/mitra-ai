package tech.beyondscale.mitra.data.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.MitraLog
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.db.Outbox
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.domain.repository.SyncController

/** Platform hook to try again later (Android: WorkManager job that waits for a network). */
fun interface SyncScheduler { fun scheduleRetry() }

/** Per-entity reaction to a sent outbox row. Called only if the row was still queued when the answer came. */
interface SyncHandler {
    suspend fun onSuccess(response: JsonElement?)
    /** The server refused the write (4xx, or a third `500 INTERNAL`); the local copy should be restored from the server. */
    suspend fun onRejected(error: AppError)
    /** Like [onRejected], with the refused outbox row (method, path, entityId, body). Defaults to [onRejected]. */
    suspend fun onRejected(error: AppError, row: Outbox) = onRejected(error)
}

/** Sends outbox rows one at a time, oldest first. See spec §6 for the failure rules. */
@OptIn(FlowPreview::class)
class SyncEngine(
    private val api: ApiClient,
    private val db: MitraDb,
    private val scheduler: SyncScheduler,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    debounceMs: Long = 500,
) : SyncController {
    enum class Result { Drained, Offline, Retry, SignedOut }

    private val handlers = mutableMapOf<String, SyncHandler>()
    private val mutex = Mutex()
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    // A Channel, not a SharedFlow: a rejection while no screen is collecting (app in background, Login showing)
    // must still reach the user once the shell is back.
    private val _messages = Channel<String>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _acks = MutableStateFlow(0L)
    private val _drained = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val messages: Flow<String> = _messages.receiveAsFlow()
    override val pendingCount: Flow<Long> = db.outboxQueries.count().asFlow().mapToOne(io)

    /** Number of rows that have left the outbox after a server answer; bumped *before* the row is removed. */
    val acknowledged: StateFlow<Long> = _acks.asStateFlow()

    /** Emits when a drain that sent at least one row has emptied the queue (modules may refresh). */
    val drained: Flow<Unit> = _drained.asSharedFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            requests.debounce(debounceMs).collect {
                try { drain() } catch (e: CancellationException) { throw e } catch (e: Exception) { MitraLog.warn(TAG, "drain failed", e) }
            }
        }
    }

    /** Handlers must be registered at DI setup, before the first drain; the map is not synchronised. */
    fun register(entity: String, handler: SyncHandler) { handlers[entity] = handler }

    override fun requestSync() { requests.tryEmit(Unit) }

    /**
     * Sends queued rows until the outbox is empty or a send fails. Pass [scheduleRetryOnFailure] = false when the
     * caller retries on its own (the WorkManager job returns `Result.retry()`), so no extra job is chained.
     */
    suspend fun drain(scheduleRetryOnFailure: Boolean = true): Result = mutex.withLock { drainLocked(scheduleRetryOnFailure) }

    /**
     * [drain] for a background job (WorkManager), retry left to the caller. The drain runs in the engine's scope, so
     * cancelling the caller — e.g. the periodic job being cancelled because this very drain emptied the outbox — only
     * stops the wait, never a send or handler midway.
     */
    suspend fun drainDetached(): Result = scope.async { drain(scheduleRetryOnFailure = false) }.await()

    private suspend fun drainLocked(scheduleRetryOnFailure: Boolean): Result {
        var result: Result? = null
        var sent = 0
        while (result == null) {
            // Claim atomically so a concurrent enqueue can't merge into a row after we read its body.
            val claimed = withContext(io) {
                db.transactionWithResult {
                    val next = db.outboxQueries.selectNext().executeAsOneOrNull()
                    next?.also { db.outboxQueries.markInFlight(it.id) } to db.outboxQueries.count().executeAsOne()
                }
            }
            val entry = claimed.first
            val pending = claimed.second
            if (entry == null) {
                _status.value = SyncStatus.Idle
                if (sent > 0) _drained.tryEmit(Unit)
                result = Result.Drained
                continue
            }
            _status.value = SyncStatus.Syncing(pending)

            val body = try {
                entry.body?.let { api.json.parseToJsonElement(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sent++
                reject(entry, AppError.Unknown(e))
                continue
            }
            when (val r = api.send(HttpMethod.parse(entry.method), entry.path, body)) {
                is Outcome.Ok -> {
                    sent++
                    if (dequeue(entry.id)) safely { handlers[entry.entity]?.onSuccess(r.value) }
                }
                is Outcome.Err -> {
                    val e = r.error
                    // A plain `500 INTERNAL` from this API is an unhandled exception (e.g. a create resent after its
                    // first response was lost hits the existing id). It will never succeed; don't block the queue on
                    // it. Only those answers count — offline tries, timeouts and 401s don't — and a 500 without the
                    // API's envelope (code "ERROR", e.g. from a proxy) is an outage like 502–504, never dropped.
                    val poisoned = e is AppError.Server && e.status == 500 && e.code == "INTERNAL" &&
                        recordServerError(entry.id) >= POISON_SERVER_ERRORS
                    val transient = !poisoned &&
                        (e is AppError.Offline || (e is AppError.Server && (e.status >= 500 || e.status == 429)))
                    if (transient || e is AppError.Unauthenticated) {
                        withContext(io) { db.outboxQueries.clearInFlight(entry.id) }
                        if (transient && scheduleRetryOnFailure) scheduler.scheduleRetry()
                        _status.value = when (e) {
                            is AppError.Offline -> SyncStatus.Offline(pending)
                            is AppError.Unauthenticated -> SyncStatus.Idle
                            else -> SyncStatus.Retrying(pending)
                        }
                        result = when (e) {
                            is AppError.Offline -> Result.Offline
                            is AppError.Unauthenticated -> Result.SignedOut
                            else -> Result.Retry
                        }
                    } else {
                        sent++
                        reject(entry, e)
                    }
                }
            }
        }
        return result
    }

    /** Counts one more `500 INTERNAL` for the row; returns its total (0 if the row is already gone). */
    private suspend fun recordServerError(id: String): Long = withContext(io) {
        db.transactionWithResult {
            db.outboxQueries.recordServerError(id)
            db.outboxQueries.serverErrors(id).executeAsOneOrNull() ?: 0L
        }
    }

    /**
     * Removes a sent row; false if it was already gone (logout/account switch cleared the outbox while the request
     * was on the wire) — then its response belongs to a session that no longer exists and must be ignored.
     */
    private suspend fun dequeue(id: String): Boolean {
        _acks.update { it + 1 }
        return withContext(io) {
            db.transactionWithResult {
                val queued = db.outboxQueries.exists(id).executeAsOne() > 0
                db.outboxQueries.delete(id)
                queued
            }
        }
    }

    private suspend fun reject(entry: Outbox, error: AppError) {
        if (!dequeue(entry.id)) return
        safely { handlers[entry.entity]?.onRejected(error, entry) }
        _messages.trySend("Couldn't save · ${error.message}")
    }

    /** A misbehaving handler must not break the queue; the row outcome already applied stands. */
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { MitraLog.warn(TAG, "sync handler failed", e) }
    }

    private companion object {
        const val POISON_SERVER_ERRORS = 3
        const val TAG = "MitraSync"
    }
}
