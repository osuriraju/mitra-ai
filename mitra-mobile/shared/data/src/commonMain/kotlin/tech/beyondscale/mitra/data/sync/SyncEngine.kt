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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.domain.repository.SyncController

/** Platform hook to try again later (Android: WorkManager job that waits for a network). */
fun interface SyncScheduler { fun scheduleRetry() }

/** Per-entity reaction to a sent outbox row. */
interface SyncHandler {
    suspend fun onSuccess(response: JsonElement?)
    /** The server refused the write (4xx); the local copy should be restored from the server. */
    suspend fun onRejected(error: AppError)
}

/** Sends outbox rows one at a time, oldest first. See spec §6 for the failure rules. */
@OptIn(FlowPreview::class)
class SyncEngine(
    private val api: ApiClient,
    private val db: MitraDb,
    private val scheduler: SyncScheduler,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    debounceMs: Long = 500,
) : SyncController {
    enum class Result { Drained, Offline, Retry, SignedOut }

    private val handlers = mutableMapOf<String, SyncHandler>()
    private val mutex = Mutex()
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val messages: Flow<String> = _messages
    override val pendingCount: Flow<Long> = db.outboxQueries.count().asFlow().mapToOne(io)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            requests.debounce(debounceMs).collect {
                try { drain() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    /** Handlers must be registered at DI setup, before the first drain; the map is not synchronised. */
    fun register(entity: String, handler: SyncHandler) { handlers[entity] = handler }

    override fun requestSync() { requests.tryEmit(Unit) }

    suspend fun drain(): Result = mutex.withLock { drainLocked() }

    private suspend fun drainLocked(): Result {
        var result: Result? = null
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
                result = Result.Drained
                continue
            }
            _status.value = SyncStatus.Syncing(pending)

            val body = try {
                entry.body?.let { api.json.parseToJsonElement(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reject(entry.id, entry.entity, AppError.Unknown(e))
                continue
            }
            when (val r = api.send(HttpMethod.parse(entry.method), entry.path, body)) {
                is Outcome.Ok -> {
                    withContext(io) { db.outboxQueries.delete(entry.id) }
                    safely { handlers[entry.entity]?.onSuccess(r.value) }
                }
                is Outcome.Err -> {
                    val e = r.error
                    val transient = e is AppError.Offline || (e is AppError.Server && (e.status >= 500 || e.status == 429))
                    if (transient || e is AppError.Unauthenticated) {
                        withContext(io) { db.outboxQueries.clearInFlight(entry.id) }
                        if (transient) scheduler.scheduleRetry()
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
                        reject(entry.id, entry.entity, e)
                    }
                }
            }
        }
        return result
    }

    private suspend fun reject(id: String, entity: String, error: AppError) {
        withContext(io) { db.outboxQueries.delete(id) }
        safely { handlers[entity]?.onRejected(error) }
        _messages.tryEmit("Couldn't save · ${error.message}")
    }

    /** A misbehaving handler must not break the queue; the row outcome already applied stands. */
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
}
