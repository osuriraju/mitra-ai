package tech.beyondscale.mitra.data.repository

import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.local.LocalData
import tech.beyondscale.mitra.data.local.PrefKeys
import tech.beyondscale.mitra.data.local.PrefStore
import tech.beyondscale.mitra.data.local.UserCache
import tech.beyondscale.mitra.data.remote.ApiClient
import tech.beyondscale.mitra.data.remote.InMemorySecureStore
import tech.beyondscale.mitra.data.remote.MockServer
import tech.beyondscale.mitra.data.remote.PersistentCookiesStorage
import tech.beyondscale.mitra.data.remote.TEST_BASE
import tech.beyondscale.mitra.data.remote.UserDto
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.remote.toDomain
import tech.beyondscale.mitra.data.remote.userJson
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.ModuleKey
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class SettingsRepositoryImplTest {
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val cookies = PersistentCookiesStorage(InMemorySecureStore(), clock)
    private val api = ApiClient(server.engine, TEST_BASE, cookies)

    private class Env(val auth: AuthRepositoryImpl, val settings: SettingsRepositoryImpl, val engine: SyncEngine)

    private fun TestScope.env(): Env {
        val io = UnconfinedTestDispatcher(testScheduler)
        val cache = UserCache(db, api.json, io)
        val engine = SyncEngine(api, db, {}, backgroundScope, io)
        val prefs = PrefStore(db, io)
        val auth = AuthRepositoryImpl(api, db, cache, prefs, LocalData(db), cookies, engine, backgroundScope, io)
        val settings = SettingsRepositoryImpl(db, cache, prefs, OutboxWriter(db, api.json, clock), engine, auth, api.json, io)
        db.userQueries.upsert("u1", api.json.encodeToString(UserDto.serializer(), UserDto("u1", "Raju Osuri", "raju@example.com")))
        db.prefQueries.put(PrefKeys.LAST_USER_ID, "u1")
        return Env(auth, settings, engine)
    }

    @Test fun toggleAppliesLocallyAndPatchesServer() = runTest {
        server.routes["PATCH /api/users/me/settings"] = { req ->
            assertEquals("""{"modules":{"money":false,"wellness":true,"ai":true}}""", req.body.toByteArray().decodeToString())
            json(userJson(settings = """{"modules":{"money":false,"wellness":true,"ai":true}}"""))
        }
        server.offline = true
        val e = env()
        e.auth.restore(); runCurrent()
        e.settings.setModule(ModuleKey.Money, false); runCurrent()
        assertEquals(Modules(money = false), assertIs<SessionState.SignedIn>(e.auth.session.value).user.settings.modules)

        server.offline = false
        e.engine.drain(); runCurrent()
        assertEquals(listOf("PATCH /api/users/me/settings"), server.log.filter { it.startsWith("PATCH") })
    }

    @Test fun serverEchoDoesNotOverwriteNewerLocalChange() = runTest {
        val e = env()
        var reentered = false
        server.routes["PATCH /api/users/me/settings"] = { req ->
            val sent = req.body.toByteArray().decodeToString()
            // While the first PATCH is on the wire, the user flips AI off too.
            if (!reentered) { reentered = true; e.settings.setModule(ModuleKey.Ai, false) }
            json(userJson(settings = sent)) // the server echoes the merged settings it now holds
        }
        server.offline = true
        e.auth.restore(); runCurrent()
        e.settings.setModule(ModuleKey.Money, false)
        server.offline = false
        e.engine.drain(); runCurrent()

        val modules = assertIs<SessionState.SignedIn>(e.auth.session.value).user.settings.modules
        assertEquals(Modules(money = false, ai = false), modules)
        assertEquals(2, server.log.count { it == "PATCH /api/users/me/settings" })
    }

    @Test fun echoArrivingAfterLogoutDoesNotRecacheUser() = runTest {
        val release = CompletableDeferred<Unit>()
        server.routes["PATCH /api/users/me/settings"] = { req ->
            val sent = req.body.toByteArray().decodeToString()
            release.await()
            json(userJson(settings = sent))
        }
        server.offline = true
        val e = env()
        e.auth.restore(); runCurrent()
        e.settings.setModule(ModuleKey.Money, false)
        server.offline = false
        val drain = backgroundScope.launch { e.engine.drain() }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { while (server.log.none { it == "PATCH /api/users/me/settings" }) delay(10) } }

        e.auth.logout(); runCurrent()
        release.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { drain.join() } }
        runCurrent()

        assertNull(db.userQueries.selectCurrent().executeAsOneOrNull())
        assertEquals(SessionState.SignedOut, e.auth.session.value)
    }

    @Test fun rapidTogglesOfDifferentModulesBothLand() = runTest {
        server.offline = true
        val e = env()
        e.auth.restore(); runCurrent()
        e.settings.setModule(ModuleKey.Money, false)
        e.settings.setModule(ModuleKey.Ai, false)

        val cached = assertNotNull(UserCache(db, api.json, UnconfinedTestDispatcher(testScheduler)).current())
        assertEquals(Modules(money = false, ai = false), cached.toDomain(api.json).settings.modules)
        val row = db.outboxQueries.selectAll().executeAsList().single()
        assertEquals("""{"modules":{"money":false,"wellness":true,"ai":false}}""", row.body)
    }

    @Test fun shareWellnessMergesWithAQueuedModuleToggle() = runTest {
        server.offline = true
        val e = env()
        e.auth.restore(); runCurrent()
        e.settings.setModule(ModuleKey.Ai, false)
        e.settings.setShareWellness(true)

        val cached = assertNotNull(UserCache(db, api.json, UnconfinedTestDispatcher(testScheduler)).current())
        assertEquals(true, cached.toDomain(api.json).settings.shareWellness)
        val row = db.outboxQueries.selectAll().executeAsList().single()
        assertEquals("""{"modules":{"money":true,"wellness":true,"ai":false},"shareWellness":true}""", row.body)
    }
}
