package tech.beyondscale.mitra.data.repository

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.http.Cookie
import io.ktor.http.Url
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
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
import tech.beyondscale.mitra.data.remote.errorJson
import tech.beyondscale.mitra.data.remote.json
import tech.beyondscale.mitra.data.remote.userJson
import tech.beyondscale.mitra.data.sync.OutboxWriter
import tech.beyondscale.mitra.data.testDriver
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.testing.FakeSyncController
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.awaitCancellation

class AuthRepositoryImplTest {
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"))
    private val server = MockServer()
    private val db = MitraDb(testDriver())
    private val secure = InMemorySecureStore()
    private val cookies = PersistentCookiesStorage(secure, clock)
    private val api = ApiClient(server.engine, TEST_BASE, cookies)
    private val sync = FakeSyncController()
    private val writer = OutboxWriter(db, api.json, clock)

    private fun TestScope.repo(): AuthRepositoryImpl {
        val io = UnconfinedTestDispatcher(testScheduler)
        return AuthRepositoryImpl(api, db, UserCache(db, api.json, io), PrefStore(db, io), LocalData(db), cookies, sync, backgroundScope, io)
    }
    private fun seedCachedUser(id: String = "u1") {
        db.userQueries.upsert(id, api.json.encodeToString(UserDto.serializer(), UserDto(id, "Raju Osuri", "raju@example.com")))
        db.prefQueries.put(PrefKeys.LAST_USER_ID, id)
    }
    private fun queueOne() = writer.enqueue("t", "PATCH", "x", buildJsonObject { put("v", 1) })
    private fun outboxSize() = db.outboxQueries.count().executeAsOne()

    @Test fun loginCachesUserAndSignsIn() = runTest {
        server.routes["POST /api/auth/login"] = { json(userJson("u1")) }
        val repo = repo()
        assertIs<Outcome.Ok<*>>(repo.login("raju", "pw"))
        runCurrent()
        assertEquals("Raju Osuri", assertIs<SessionState.SignedIn>(repo.session.value).user.name)
        assertEquals("u1", db.prefQueries.get(PrefKeys.LAST_USER_ID).executeAsOneOrNull())
        assertEquals(1, sync.requests)
    }

    @Test fun wrongPasswordLeavesSessionSignedOut() = runTest {
        server.routes["GET /api/auth/me"] = { json(errorJson("UNAUTHENTICATED", "Please sign in"), HttpStatusCode.Unauthorized) }
        server.routes["POST /api/auth/login"] = { json(errorJson("BAD_CREDENTIALS", "Email/username or password is incorrect"), HttpStatusCode.Unauthorized) }
        val repo = repo()
        repo.restore(); runCurrent()
        val err = assertIs<Outcome.Err>(repo.login("raju", "nope")).error
        assertEquals("Email/username or password is incorrect", err.message)
        assertEquals(SessionState.SignedOut, repo.session.value)
    }

    @Test fun sameUserReloginKeepsQueuedChanges() = runTest {
        seedCachedUser("u1"); queueOne()
        server.routes["POST /api/auth/login"] = { json(userJson("u1")) }
        repo().login("raju", "pw")
        assertEquals(1L, outboxSize())
    }

    @Test fun differentUserLoginWipesPreviousData() = runTest {
        seedCachedUser("u1"); queueOne()
        server.routes["POST /api/auth/login"] = { json(userJson("u2", name = "Someone Else")) }
        val repo = repo()
        repo.login("other", "pw"); runCurrent()
        assertEquals(0L, outboxSize())
        assertEquals("Someone Else", assertIs<SessionState.SignedIn>(repo.session.value).user.name)
    }

    @Test fun restoreShowsCachedUserEvenOffline() = runTest {
        seedCachedUser(); server.offline = true
        val repo = repo()
        repo.restore(); runCurrent()
        assertIs<SessionState.SignedIn>(repo.session.value)
    }

    @Test fun restoreWithoutCacheAndNoSessionSignsOut() = runTest {
        server.routes["GET /api/auth/me"] = { json(errorJson("UNAUTHENTICATED", "Please sign in"), HttpStatusCode.Unauthorized) }
        val repo = repo()
        repo.restore(); runCurrent()
        assertEquals(SessionState.SignedOut, repo.session.value)
    }

    @Test fun restoreResetsInFlightRows() = runTest {
        seedCachedUser(); queueOne(); server.offline = true
        db.outboxQueries.markInFlight(db.outboxQueries.selectNext().executeAsOne().id)
        repo().restore()
        assertEquals(0L, db.outboxQueries.selectNext().executeAsOne().inFlight)
    }

    @Test fun expiredSessionSignsOutButKeepsLocalData() = runTest {
        seedCachedUser(); queueOne()
        server.routes["GET /api/auth/me"] = { json(errorJson("UNAUTHENTICATED", "Please sign in"), HttpStatusCode.Unauthorized) }
        val repo = repo()
        runCurrent()
        repo.restore(); runCurrent()
        // The background refresh runs on Ktor's engine thread, so wait for its real completion rather than the virtual clock.
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { repo.session.first { it == SessionState.SignedOut } } }
        assertEquals(SessionState.SignedOut, repo.session.value)
        assertEquals(1L, outboxSize())
        assertTrue(db.userQueries.selectCurrent().executeAsOneOrNull() != null)
    }

    @Test fun logoutWorksOfflineAndClearsEverything() = runTest {
        seedCachedUser(); queueOne()
        cookies.addCookie(Url(TEST_BASE), Cookie("mitra_sid", "abc"))
        server.offline = true
        val repo = repo()
        repo.restore(); runCurrent()
        repo.logout(); runCurrent()
        assertEquals(SessionState.SignedOut, repo.session.value)
        assertEquals(0L, outboxSize())
        assertNull(db.userQueries.selectCurrent().executeAsOneOrNull())
        assertTrue(cookies.get(Url(TEST_BASE)).isEmpty())
    }

    @Test fun hasLocalAccountFollowsCachedUser() = runTest {
        server.routes["POST /api/auth/login"] = { json(userJson("u1")) }
        server.routes["POST /api/auth/logout"] = { json("""{"ok":true}""") }
        val repo = repo()
        assertEquals(false, repo.hasLocalAccount.first())
        repo.login("raju", "pw"); runCurrent()
        assertEquals(true, repo.hasLocalAccount.first())
        repo.logout(); runCurrent()
        assertEquals(false, repo.hasLocalAccount.first())
    }

    @Test fun forgotPasswordPostsEmail() = runTest {
        server.routes["POST /api/auth/forgot-password"] = { json("""{"ok":true}""") }
        assertEquals(Outcome.Ok(Unit), repo().forgotPassword("raju@example.com"))
    }

    @Test fun refreshKeepsQueuedSettingsOverServerCopy() = runTest {
        seedCachedUser()
        writer.enqueue("user-settings", "PATCH", "users/me/settings", buildJsonObject {
            put("modules", buildJsonObject { put("money", false); put("wellness", true); put("ai", true) })
        }, coalesceKey = "user-settings")
        server.routes["GET /api/auth/me"] = { json(userJson("u1", settings = """{"modules":{"money":true,"wellness":true,"ai":true}}""")) }
        val repo = repo()
        repo.restore(); runCurrent()
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { while (server.log.none { it == "GET /api/auth/me" }) delay(10) } }
        assertEquals(false, withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { repo.session.first { it is SessionState.SignedIn && !it.user.settings.modules.money } }
        }.let { (it as SessionState.SignedIn).user.settings.modules.money })
        assertEquals(1L, outboxSize())
    }

    @Test fun logoutWhileRefreshInFlightStaysSignedOut() = runTest {
        seedCachedUser()
        val release = CompletableDeferred<Unit>()
        server.routes["GET /api/auth/me"] = { release.await(); json(userJson("u1")) }
        val repo = repo()
        repo.restore(); runCurrent()
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { while (server.requests.none { it.url.encodedPath == "/api/auth/me" }) delay(10) } }
        server.offline = true
        repo.logout(); runCurrent()
        release.complete(Unit)
        withContext(Dispatchers.Default) { delay(200) }
        runCurrent()
        assertEquals(SessionState.SignedOut, repo.session.value)
        assertNull(db.userQueries.selectCurrent().executeAsOneOrNull())
        assertNull(db.prefQueries.get(PrefKeys.LAST_USER_ID).executeAsOneOrNull())
    }

    @Test fun logoutDoesNotWaitOnAHangingNetwork() = runTest {
        seedCachedUser()
        server.routes["POST /api/auth/logout"] = { awaitCancellation() } // captive portal: the request never answers
        val repo = repo()
        repo.restore(); runCurrent()
        val started = TimeSource.Monotonic.markNow()
        repo.logout(); runCurrent()
        assertTrue(started.elapsedNow() < 10.seconds, "logout took ${started.elapsedNow()}")
        assertEquals(SessionState.SignedOut, repo.session.value)
        assertNull(db.userQueries.selectCurrent().executeAsOneOrNull())
    }

    private suspend fun TestScope.unauthorizedDuringRefresh(meStatus: HttpStatusCode): AuthRepositoryImpl {
        seedCachedUser()
        val release = CompletableDeferred<Unit>()
        server.routes["GET /api/auth/me"] = {
            release.await()
            if (meStatus == HttpStatusCode.OK) json(userJson("u1")) else json(errorJson("UNAVAILABLE", "down"), meStatus)
        }
        server.routes["PATCH /api/x"] = { json(errorJson("UNAUTHENTICATED", "Please sign in"), HttpStatusCode.Unauthorized) }
        val repo = repo()
        repo.restore(); runCurrent()
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { while (server.requests.none { it.url.encodedPath == "/api/auth/me" }) delay(10) } }
        api.send(io.ktor.http.HttpMethod.Patch, "x", null) // another request's 401 lands while the refresh is in flight
        runCurrent()
        assertIs<SessionState.SignedIn>(repo.session.value)
        release.complete(Unit)
        withContext(Dispatchers.Default) { delay(200) }
        runCurrent()
        return repo
    }

    @Test fun unauthorizedDuringFailedRefreshStillSignsOut() = runTest {
        val repo = unauthorizedDuringRefresh(HttpStatusCode.ServiceUnavailable)
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { repo.session.first { it == SessionState.SignedOut } } }
    }

    @Test fun unauthorizedDuringSuccessfulRefreshIsStale() = runTest {
        val repo = unauthorizedDuringRefresh(HttpStatusCode.OK)
        assertIs<SessionState.SignedIn>(repo.session.value)
    }
}
