package tech.beyondscale.mitra.data.repository

import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.toDomain
import tech.beyondscale.mitra.data.local.toRow
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.errorJson
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.sync.OutboxEntities
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.WellnessChange
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessMetric
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.model.WellnessSettingsChange
import tech.beyondscale.mitra.domain.repository.WellnessRepository
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class WellnessRepositoryImplTest {
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00+05:30"), TimeZone.of("Asia/Kolkata"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val api = ApiClient(server.engine, TEST_BASE, PersistentCookiesStorage(InMemorySecureStore(), clock))
    private val writer = OutboxWriter(db, api.json, clock)

    private class Env(val repo: WellnessRepositoryImpl, val engine: SyncEngine, val prefs: PrefStore)

    private fun TestScope.env(): Env {
        val io = UnconfinedTestDispatcher(testScheduler)
        val engine = SyncEngine(api, db, {}, backgroundScope, io, debounceMs = 60_000)
        val prefs = PrefStore(db, io)
        prefs.put(PrefKeys.LAST_USER_ID, "u1")
        return Env(WellnessRepositoryImpl(api, db, prefs, writer, engine, clock, backgroundScope, io), engine, prefs)
    }

    private val settingsJson =
        """{"height":180,"sleepTarget":480,"stepsTarget":9000,"waterTarget":10,"track":{"mood":true,"sleep":true,"steps":false,"water":true,"bmi":true},"shareAI":true,"showOnToday":false,"morningReminder":"07:00","eveningReminder":"22:00"}"""
    private fun bootstrap(entries: String = "{}", settings: String = settingsJson) = """{"entries":$entries,"settings":$settings}"""

    private fun seed(e: WellnessEntry) = db.wellnessEntriesQueries.upsert(e.toRow())
    private fun entry(date: String) = db.wellnessEntriesQueries.selectByDate(date).executeAsOneOrNull()?.toDomain()
    private fun dates() = db.wellnessEntriesQueries.selectDates().executeAsList().sorted()
    private fun storedSettings() = db.wellnessSettingsQueries.select().executeAsOneOrNull()?.toDomain()
    private fun queue() = db.outboxQueries.selectAll().executeAsList()

    /* ---------- refresh ---------- */

    @Test fun defaultsBeforeFirstRefresh() = runTest {
        val e = env()
        assertEquals(WellnessSettings(), e.repo.settings.first())
        assertEquals(emptyMap(), e.repo.entries.first())
    }

    @Test fun refreshStoresEntriesAndSettings() = runTest {
        val e = env()
        seed(WellnessEntry("2026-01-01", mood = 2, savedAt = "2026-01-01")) // outside the server's window: kept
        seed(WellnessEntry("2026-10-01", mood = 1, savedAt = "2026-10-01")) // inside and absent: dropped
        server.routes["GET /api/wellness/bootstrap"] = {
            json(bootstrap("""{"2026-10-13":{"mood":3,"sleepStart":"23:30","sleepEnd":"06:45","steps":7200,"weight":72.5,"savedAt":"2026-10-13"}}"""))
        }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(listOf("2026-01-01", "2026-10-13"), dates())
        assertEquals(WellnessEntry("2026-10-13", 3, "23:30", "06:45", 7200, null, 72.5, null, "2026-10-13"), entry("2026-10-13"))
        val s = e.repo.settings.first()
        assertEquals(180, s.height)
        assertFalse(s.track.steps)
        assertTrue(s.shareAI)
        assertEquals("22:00", s.eveningReminder)
    }

    @Test fun refreshKeepsPendingDayAndSettings() = runTest {
        val e = env()
        server.offline = true
        e.repo.saveEntry("2026-10-14", listOf(WellnessChange.Mood(4)))
        e.repo.updateSettings(listOf(WellnessSettingsChange.Height(165)))
        server.offline = false
        server.routes["GET /api/wellness/bootstrap"] = { json(bootstrap("""{"2026-10-13":{"mood":1,"savedAt":"2026-10-13"}}""")) }
        assertIs<Outcome.Ok<Unit>>(e.repo.refresh())
        assertEquals(4, entry("2026-10-14")!!.mood)
        assertEquals(1, entry("2026-10-13")!!.mood)
        assertEquals(165, storedSettings()!!.height)
    }

    @Test fun refreshRacingAWriteIsDiscarded() = runTest {
        val e = env()
        var gets = 0
        // runTest may skip ahead to the engine's debounced drain while the mock thread blocks: its PUT must not be
        // refused (a refusal drops the row and refreshes the day away).
        server.routes["PUT /api/wellness/entries/2026-10-14"] = { throw Exception("network down") }
        server.routes["GET /api/wellness/bootstrap"] = {
            gets++
            if (gets == 1) e.repo.saveEntry("2026-10-14", listOf(WellnessChange.Mood(3)))
            json(bootstrap())
        }
        val result = e.repo.refresh()
        assertTrue(result is Outcome.Ok || (result as Outcome.Err).error.let { it is AppError.Server && it.code == WellnessRepository.REFRESH_SUPERSEDED })
        assertEquals(3, entry("2026-10-14")!!.mood)
    }

    @Test fun refreshAfterSignOutWritesNothing() = runTest {
        val e = env()
        server.routes["GET /api/wellness/bootstrap"] = {
            e.prefs.remove(PrefKeys.LAST_USER_ID)
            json(bootstrap("""{"2026-10-13":{"mood":3,"savedAt":"2026-10-13"}}"""))
        }
        e.repo.refresh()
        assertEquals(emptyList(), dates())
        assertNull(storedSettings())
    }

    /* ---------- writes ---------- */

    @Test fun saveEntryStampsNewDayAndCoalescesPerDay() = runTest {
        val e = env()
        server.offline = true
        e.repo.saveEntry("2026-10-14", listOf(WellnessChange.Mood(3), WellnessChange.Note("  ")))
        e.repo.saveEntry("2026-10-14", listOf(WellnessChange.Steps(5000), WellnessChange.Mood(null)))
        e.repo.saveEntry("2026-10-14", emptyList())
        assertEquals(WellnessEntry("2026-10-14", steps = 5000, savedAt = "2026-10-14"), e.repo.entries.first()["2026-10-14"])
        val row = queue().single()
        assertEquals("PUT wellness/entries/2026-10-14", "${row.method} ${row.path}")
        assertEquals(OutboxEntities.WELLNESS_ENTRY, row.entity)
        assertEquals("2026-10-14", row.entityId)
        assertEquals("""{"mood":null,"note":null,"steps":5000}""", row.body)
    }

    @Test fun differentDaysQueueSeparately() = runTest {
        val e = env()
        seed(WellnessEntry("2026-10-13", mood = 2, sleepStart = "23:00", savedAt = "2026-10-13"))
        server.offline = true
        e.repo.saveEntry("2026-10-13", listOf(WellnessChange.SleepEnd("06:30")))
        e.repo.saveEntry("2026-10-14", listOf(WellnessChange.Weight(72.4)))
        assertEquals(listOf("wellness/entries/2026-10-13", "wellness/entries/2026-10-14"), queue().map { it.path })
        assertEquals(WellnessEntry("2026-10-13", 2, "23:00", "06:30", savedAt = "2026-10-13"), entry("2026-10-13"))
    }

    @Test fun settingsTogglesMergeTrackAndCoalesce() = runTest {
        val e = env()
        server.offline = true
        e.repo.updateSettings(listOf(WellnessSettingsChange.Track(WellnessMetric.Steps, false)))
        e.repo.updateSettings(listOf(WellnessSettingsChange.Track(WellnessMetric.Bmi, false), WellnessSettingsChange.SleepTarget(420)))
        e.repo.updateSettings(listOf(WellnessSettingsChange.ShareAI(true)))
        val s = e.repo.settings.first()
        assertFalse(s.track.steps)
        assertFalse(s.track.bmi)
        assertEquals(420, s.sleepTarget)
        val row = queue().single()
        assertEquals("PATCH wellness/settings", "${row.method} ${row.path}")
        assertNull(row.entityId)
        assertEquals("""{"track":{"steps":false,"bmi":false},"sleepTarget":420,"shareAI":true}""", row.body)
    }

    @Test fun echoAppliesServerCopyOnlyWhenNothingQueued() = runTest {
        val e = env()
        server.offline = true
        e.repo.saveEntry("2026-10-14", listOf(WellnessChange.Mood(3)))
        e.repo.saveEntry("2026-10-13", listOf(WellnessChange.Water(4)))
        e.repo.saveEntry("2026-10-14", listOf(WellnessChange.Steps(100))) // not the newest row on its own key → a new row
        server.offline = false
        var puts = 0
        server.routes["PUT /api/wellness/entries/2026-10-14"] = { req ->
            puts++
            if (puts == 1) {
                json("""{"date":"2026-10-14","entry":{"mood":3,"savedAt":"2026-10-14"}}""") // a newer row for the day is queued
            } else {
                assertEquals(100, entry("2026-10-14")!!.steps) // the first echo was skipped
                assertTrue("\"steps\":100" in req.body.toByteArray().decodeToString())
                json("""{"date":"2026-10-14","entry":{"mood":3,"steps":100,"note":"web","savedAt":"2026-10-14"}}""")
            }
        }
        server.routes["PUT /api/wellness/entries/2026-10-13"] = { json("""{"date":"2026-10-13","entry":{"water":4,"savedAt":"2026-10-13"}}""") }
        server.routes["PATCH /api/wellness/settings"] = { json(settingsJson) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(2, puts)
        assertEquals("web", entry("2026-10-14")!!.note)
        assertEquals(4, entry("2026-10-13")!!.water)
    }

    @Test fun settingsEchoIsStored() = runTest {
        val e = env()
        server.offline = true
        e.repo.updateSettings(listOf(WellnessSettingsChange.Height(180)))
        server.offline = false
        server.routes["PATCH /api/wellness/settings"] = { json(settingsJson) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(10, storedSettings()!!.waterTarget)
        assertEquals(emptyList(), queue())
    }

    @Test fun rejectedWriteRefreshes() = runTest {
        val e = env()
        server.offline = true
        e.repo.saveEntry("2026-10-14", listOf(WellnessChange.Steps(999)))
        server.offline = false
        server.routes["PUT /api/wellness/entries/2026-10-14"] = { json(errorJson("VALIDATION", "Bad steps"), HttpStatusCode.BadRequest) }
        server.routes["GET /api/wellness/bootstrap"] = { json(bootstrap("""{"2026-10-13":{"mood":1,"savedAt":"2026-10-13"}}""")) }
        assertEquals(SyncEngine.Result.Drained, e.engine.drain())
        assertEquals(listOf("2026-10-13"), dates())
    }
}
