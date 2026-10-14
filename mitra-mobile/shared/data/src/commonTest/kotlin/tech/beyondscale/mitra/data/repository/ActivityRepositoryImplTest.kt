package tech.beyondscale.mitra.data.repository

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class ActivityRepositoryImplTest {
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00+05:30"), TimeZone.of("Asia/Kolkata"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val api = ApiClient(server.engine, TEST_BASE, PersistentCookiesStorage(InMemorySecureStore(), clock))

    @Test fun refreshReplacesFeed() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val prefs = PrefStore(db, io).also { it.put(PrefKeys.LAST_USER_ID, "u1") }
        val repo = ActivityRepositoryImpl(api, db, prefs, backgroundScope, io)
        server.routes["GET /api/activity"] = {
            json("""[{"id":"x1","text":"Logged mood","icon":"heart","tone":"success","module":"wellness","at":"2026-10-14T04:30:00.000Z"}]""")
        }
        assertIs<Outcome.Ok<Unit>>(repo.refresh())
        assertEquals("limit=200", server.requests.single().url.encodedQuery)
        server.routes["GET /api/activity"] = { json("""[{"id":"x2","text":"Added task","at":"2026-10-14T05:00:00.000Z"}]""") }
        assertIs<Outcome.Ok<Unit>>(repo.refresh())
        assertEquals(listOf("x2"), db.activityQueries.selectAll().executeAsList().map { it.toDomain().id })
    }
}
