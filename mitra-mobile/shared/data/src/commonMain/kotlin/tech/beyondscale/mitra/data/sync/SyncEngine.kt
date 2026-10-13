package tech.beyondscale.mitra.data.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
        scope.launch { requests.debounce(debounceMs).collect { drain() } }
    }

    fun register(entity: String, handler: SyncHandler) { handlers[entity] = handler }

    override fun requestSync() { requests.tryEmit(Unit) }

    suspend fun drain(): Result = mutex.withLock { drainLocked() }

    private suspend fun drainLocked(): Result {
        var result: Result? = null
        while (result == null) {
            val entry = withContext(io) { db.outboxQueries.selectNext().executeAsOneOrNull() }
            if (entry == null) {
                _status.value = SyncStatus.Idle
                result = Result.Drained
                continue
            }
            val pending = withContext(io) { db.outboxQueries.count().executeAsOne() }
            _status.value = SyncStatus.Syncing(pending)
            withContext(io) { db.outboxQueries.markInFlight(entry.id) }

            val body = entry.body?.let { api.json.parseToJsonElement(it) }
            when (val r = api.send(HttpMethod.parse(entry.method), entry.path, body)) {
                is Outcome.Ok -> {
                    withContext(io) { db.outboxQueries.delete(entry.id) }
                    handlers[entry.entity]?.onSuccess(r.value)
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
                        withContext(io) { db.outboxQueries.delete(entry.id) }
                        handlers[entry.entity]?.onRejected(e)
                        _messages.tryEmit("Couldn't save · ${e.message}")
                    }
                }
            }
        }
        return result
    }
}
