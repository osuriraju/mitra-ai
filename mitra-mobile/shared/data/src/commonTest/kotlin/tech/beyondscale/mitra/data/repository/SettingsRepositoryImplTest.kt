package tech.beyondscale.mitra.data.repository

import io.ktor.client.engine.mock.toByteArray
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
import tech.beyondscale.mitra.data.remote.userJson
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.sync.SyncEngine
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        val auth = AuthRepositoryImpl(api, db, cache, PrefStore(db, io), LocalData(db), cookies, engine, backgroundScope, io)
        val settings = SettingsRepositoryImpl(db, cache, OutboxWriter(db, api.json, clock), engine, auth, api.json, io)
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
        e.settings.updateModules(Modules(money = false)); runCurrent()
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
            if (!reentered) { reentered = true; e.settings.updateModules(Modules(money = false, ai = false)) }
            json(userJson(settings = sent)) // the server echoes the merged settings it now holds
        }
        server.offline = true
        e.auth.restore(); runCurrent()
        e.settings.updateModules(Modules(money = false))
        server.offline = false
        e.engine.drain(); runCurrent()

        val modules = assertIs<SessionState.SignedIn>(e.auth.session.value).user.settings.modules
        assertEquals(Modules(money = false, ai = false), modules)
        assertEquals(2, server.log.count { it == "PATCH /api/users/me/settings" })
    }
}
