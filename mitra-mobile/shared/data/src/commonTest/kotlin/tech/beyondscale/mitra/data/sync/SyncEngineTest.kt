package tech.beyondscale.mitra.data.sync

import app.cash.turbine.test
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.errorJson
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.SyncStatus
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SyncEngineTest {
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val api = ApiClient(server.engine, TEST_BASE, PersistentCookiesStorage(InMemorySecureStore(), clock))
    private val writer = OutboxWriter(db, api.json, clock)
    private var retries = 0
    private val successes = mutableListOf<JsonElement?>()
    private val rejections = mutableListOf<AppError>()

    private fun TestScope.engine() = SyncEngine(api, db, { retries++ }, backgroundScope, UnconfinedTestDispatcher(testScheduler), debounceMs = 500).also {
        it.register("t", object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) { successes += response }
            override suspend fun onRejected(error: AppError) { rejections += error }
        })
    }

    private fun enqueue(path: String, body: String = "x") =
        writer.enqueue("t", "PATCH", path, buildJsonObject { put("v", body) })

    private fun pending() = db.outboxQueries.selectAll().executeAsList()

    @Test fun drainsInOrderAndCallsHandler() = runTest {
        server.routes["PATCH /api/a"] = { req -> assertEquals("""{"v":"1"}""", req.body.toByteArray().decodeToString()); json("""{"ok":1}""") }
        server.routes["PATCH /api/b"] = { json("""{"ok":2}""") }
        enqueue("a", "1"); enqueue("b", "2")
        val engine = engine()
        assertEquals(SyncEngine.Result.Drained, engine.drain())
        assertEquals(listOf("PATCH /api/a", "PATCH /api/b"), server.log)
        assertEquals(2, successes.size)
        assertEquals(0, pending().size)
        assertEquals(SyncStatus.Idle, engine.status.value)
    }

    @Test fun offlineKeepsRowAndSchedulesRetry() = runTest {
        enqueue("a"); server.offline = true
        val engine = engine()
        assertEquals(SyncEngine.Result.Offline, engine.drain())
        assertEquals(1, pending().size)
        assertEquals(0L, pending().single().inFlight)
        assertEquals(SyncStatus.Offline(1), engine.status.value)
        assertEquals(1, retries)
    }

    @Test fun workerDrivenDrainLeavesRetryToTheWorker() = runTest {
        enqueue("a"); server.offline = true
        val engine = engine()
        assertEquals(SyncEngine.Result.Offline, engine.drain(scheduleRetryOnFailure = false))
        assertEquals(1, pending().size)
        assertEquals(0, retries)
    }

    @Test fun serverErrorRetriesLater() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("INTERNAL", "boom"), HttpStatusCode.InternalServerError) }
        enqueue("a")
        val engine = engine()
        assertEquals(SyncEngine.Result.Retry, engine.drain())
        assertEquals(SyncStatus.Retrying(1), engine.status.value)
        assertEquals(1, pending().size)
        assertEquals(1, retries)
    }

    @Test fun expiredSessionStopsButKeepsQueue() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("UNAUTHENTICATED", "Please sign in"), HttpStatusCode.Unauthorized) }
        enqueue("a")
        assertEquals(SyncEngine.Result.SignedOut, engine().drain())
        assertEquals(1, pending().size)
        assertEquals(0, retries)
    }

    @Test fun rejectedWriteIsDroppedReportedAndQueueContinues() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("NOT_FOUND", "Task not found"), HttpStatusCode.NotFound) }
        server.routes["PATCH /api/b"] = { json("{}") }
        enqueue("a"); enqueue("b")
        val engine = engine()
        engine.messages.test {
            assertEquals(SyncEngine.Result.Drained, engine.drain())
            assertEquals("Couldn't save · Task not found", awaitItem())
        }
        assertEquals(listOf<AppError>(AppError.Server(404, "NOT_FOUND", "Task not found")), rejections)
        assertEquals(listOf("PATCH /api/a", "PATCH /api/b"), server.log)
        assertEquals(0, pending().size)
    }

    @Test fun requestSyncIsDebounced() = runTest {
        server.routes["PATCH /api/a"] = { json("{}") }
        val engine = engine()
        runCurrent()
        enqueue("a")
        repeat(3) { engine.requestSync() }
        advanceTimeBy(499); runCurrent()
        // Deterministic: markInFlight runs on the test dispatcher before Ktor switches threads.
        pending().single().let { assertEquals(0L, it.attempts); assertEquals(0L, it.inFlight) }
        assertEquals(emptyList(), server.log)
        advanceTimeBy(2); runCurrent()
        // Ktor's engine hops to a real dispatcher, so wait in real time for the request to land.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (server.log.isEmpty()) delay(10) } }
        assertEquals(listOf("PATCH /api/a"), server.log)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (pending().isNotEmpty()) delay(10) } }
    }

    @Test fun enqueueDuringSendIsNotLost() = runTest {
        val bodies = mutableListOf<String>()
        server.routes["PATCH /api/s"] = { req ->
            bodies += req.body.toByteArray().decodeToString()
            if (bodies.size == 1) writer.enqueue("t", "PATCH", "s", buildJsonObject { put("v", "new") }, "k")
            json("{}")
        }
        writer.enqueue("t", "PATCH", "s", buildJsonObject { put("v", "old") }, "k")
        assertEquals(SyncEngine.Result.Drained, engine().drain())
        assertEquals(listOf("""{"v":"old"}""", """{"v":"new"}"""), bodies)
        assertEquals(0, pending().size)
    }

    @Test fun throwingHandlerDoesNotBreakDrain() = runTest {
        server.routes["PATCH /api/a"] = { json("{}") }
        server.routes["PATCH /api/b"] = { json("{}") }
        val engine = SyncEngine(api, db, { retries++ }, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        engine.register("t", object : SyncHandler {
            override suspend fun onSuccess(response: JsonElement?) { error("boom") }
            override suspend fun onRejected(error: AppError) {}
        })
        enqueue("a")
        assertEquals(SyncEngine.Result.Drained, engine.drain())
        enqueue("b")
        assertEquals(SyncEngine.Result.Drained, engine.drain())
        assertEquals(listOf("PATCH /api/a", "PATCH /api/b"), server.log)
        assertEquals(0, pending().size)
    }

    @Test fun unparseableBodyIsRejectedAndQueueContinues() = runTest {
        server.routes["PATCH /api/b"] = { json("{}") }
        db.outboxQueries.insert("bad", "t", "PATCH", "a", "{bad", null, 0L, null)
        enqueue("b")
        assertEquals(SyncEngine.Result.Drained, engine().drain())
        assertEquals(1, rejections.size)
        assertEquals(listOf("PATCH /api/b"), server.log)
        assertEquals(0, pending().size)
    }

    @Test fun messagesAreBufferedUntilSomeoneCollects() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("NOT_FOUND", "Task not found"), HttpStatusCode.NotFound) }
        enqueue("a")
        val engine = engine()
        assertEquals(SyncEngine.Result.Drained, engine.drain()) // nobody is collecting yet
        assertEquals("Couldn't save · Task not found", engine.messages.first())
    }

    @Test fun responseForARowClearedMidFlightIsIgnored() = runTest {
        server.routes["PATCH /api/a"] = {
            db.outboxQueries.deleteAll() // logout while the request is on the wire
            json("""{"id":"x"}""")
        }
        enqueue("a")
        assertEquals(SyncEngine.Result.Drained, engine().drain())
        assertEquals(0, successes.size)
    }

    @Test fun internalErrorRowIsDroppedAfterThreeAttempts() = runTest {
        server.routes["POST /api/a"] = { json(errorJson("INTERNAL", "Something went wrong"), HttpStatusCode.InternalServerError) }
        server.routes["PATCH /api/b"] = { json("{}") }
        writer.enqueue("t", "POST", "a", buildJsonObject { put("id", "dup") }, entityId = "dup")
        enqueue("b")
        val engine = engine()
        assertEquals(SyncEngine.Result.Retry, engine.drain())
        assertEquals(SyncEngine.Result.Retry, engine.drain())
        assertEquals(SyncEngine.Result.Drained, engine.drain()) // third 500: dropped, queue continues
        assertEquals(listOf<AppError>(AppError.Server(500, "INTERNAL", "Something went wrong")), rejections)
        assertEquals(listOf("POST /api/a", "POST /api/a", "POST /api/a", "PATCH /api/b"), server.log)
        assertEquals(0, pending().size)
    }

    @Test fun gatewayErrorsAreNeverDropped() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("ERROR", "Bad gateway"), HttpStatusCode.BadGateway) }
        enqueue("a")
        val engine = engine()
        repeat(5) { assertEquals(SyncEngine.Result.Retry, engine.drain()) }
        assertEquals(1, pending().size)
        assertEquals(0, rejections.size)
    }

    @Test fun ackCounterAndDrainedSignal() = runTest {
        server.routes["PATCH /api/a"] = { json("{}") }
        server.routes["PATCH /api/b"] = { json(errorJson("NOT_FOUND", "gone"), HttpStatusCode.NotFound) }
        enqueue("a"); enqueue("b")
        val engine = engine()
        var drainedSignals = 0
        backgroundScope.launch { engine.drained.collect { drainedSignals++ } }
        runCurrent()
        assertEquals(0L, engine.acknowledged.value)
        engine.drain(); runCurrent()
        assertEquals(2L, engine.acknowledged.value)
        assertEquals(1, drainedSignals)
        engine.drain(); runCurrent() // nothing sent → no signal
        assertEquals(1, drainedSignals)
    }

    @Test fun offlineTriesDoNotCountTowardsPoisoning() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("INTERNAL", "Something went wrong"), HttpStatusCode.InternalServerError) }
        enqueue("a")
        val engine = engine()
        server.offline = true
        assertEquals(SyncEngine.Result.Offline, engine.drain())
        assertEquals(SyncEngine.Result.Offline, engine.drain())
        server.offline = false
        assertEquals(SyncEngine.Result.Retry, engine.drain()) // first server error: still queued
        assertEquals(1L, pending().single().attempts)
        assertEquals(0, rejections.size)
    }

    @Test fun proxyInternalErrorIsNeverDropped() = runTest {
        // A 500 whose body isn't the API's error envelope (code "ERROR") comes from a proxy, not the app: an outage.
        server.routes["PATCH /api/a"] = { json(errorJson("ERROR", "Internal Server Error"), HttpStatusCode.InternalServerError) }
        enqueue("a")
        val engine = engine()
        repeat(5) { assertEquals(SyncEngine.Result.Retry, engine.drain()) }
        assertEquals(1, pending().size)
        assertEquals(0, rejections.size)
    }

    @Test fun detachedDrainFinishesWhenItsCallerIsCancelled() = runTest {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        server.routes["PATCH /api/a"] = { release.await(); json("""{"ok":1}""") }
        enqueue("a")
        val engine = engine()
        // The periodic job is cancelled as soon as the outbox looks empty — possibly while its own drain is mid-send.
        val worker = backgroundScope.launch { engine.drainDetached() }
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (server.log.isEmpty()) delay(10) } }
        worker.cancel()
        release.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (successes.isEmpty()) delay(10) } }
        assertEquals(0, pending().size)
        assertEquals(0, retries)
    }

    @Test fun throwingHandlerIsLoggedAndQueueContinues() = runTest {
        server.routes["PATCH /api/a"] = { json("{}") }
        server.routes["PATCH /api/b"] = { json("{}") }
        val logged = mutableListOf<String>()
        val previous = tech.beyondscale.mitra.core.MitraLog.sink
        tech.beyondscale.mitra.core.MitraLog.sink = { _, m, e -> logged += "$m: ${e?.message}" }
        try {
            enqueue("a"); enqueue("b")
            val engine = engine()
            engine.register("t", object : SyncHandler {
                override suspend fun onSuccess(response: JsonElement?) { error("boom") }
                override suspend fun onRejected(error: AppError) {}
            })
            assertEquals(SyncEngine.Result.Drained, engine.drain())
            assertEquals(listOf("sync handler failed: boom", "sync handler failed: boom"), logged)
            assertEquals(0, pending().size)
        } finally {
            tech.beyondscale.mitra.core.MitraLog.sink = previous
        }
    }

    @Test fun rejectionWithNoCollectorIsReplayedLater() = runTest {
        server.routes["PATCH /api/a"] = { json(errorJson("NOT_FOUND", "Task not found"), HttpStatusCode.NotFound) }
        enqueue("a")
        val engine = engine()
        engine.drain() // no screen collecting (app in background)
        engine.messages.test { assertEquals("Couldn't save · Task not found", awaitItem()) }
    }
}
