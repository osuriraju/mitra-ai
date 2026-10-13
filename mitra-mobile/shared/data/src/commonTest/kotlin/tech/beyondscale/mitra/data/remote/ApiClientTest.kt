package tech.beyondscale.mitra.data.remote

import app.cash.turbine.test
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.close
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.FieldIssue
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class ApiClientTest {
    private val server = MockServer()
    private val cookies = PersistentCookiesStorage(InMemorySecureStore(), FixedClock(Instant.parse("2026-10-13T04:30:00Z")))
    private val api = ApiClient(server.engine, TEST_BASE, cookies)

    @Test fun decodesSuccessAndSendsJsonBody() = runTest {
        server.routes["POST /api/auth/login"] = { req ->
            assertEquals("""{"identifier":"raju","password":"pw"}""", String(req.body.toByteArray()))
            json(userJson())
        }
        val r = api.call<UserEnvelope>(HttpMethod.Post, "auth/login", buildJsonObject { put("identifier", "raju"); put("password", "pw") })
        assertEquals("Raju Osuri", assertIs<Outcome.Ok<UserEnvelope>>(r).value.user.name)
    }

    @Test fun sessionCookieIsStoredAndSentBack() = runTest {
        server.routes["POST /api/auth/login"] = { json(userJson(), HttpStatusCode.OK, HttpHeaders.SetCookie to "mitra_sid=abc.sig%2B1; Path=/; HttpOnly; SameSite=Lax") }
        server.routes["GET /api/auth/me"] = { req ->
            assertTrue(req.headers[HttpHeaders.Cookie].orEmpty().contains("mitra_sid=abc.sig%2B1"), "cookie header was ${req.headers[HttpHeaders.Cookie]}")
            json(userJson())
        }
        api.send(HttpMethod.Post, "auth/login", buildJsonObject { })
        assertIs<Outcome.Ok<*>>(api.send(HttpMethod.Get, "auth/me"))
    }

    @Test fun badCredentialsIsUnauthenticatedButDoesNotSignOut() = runTest {
        server.routes["POST /api/auth/login"] = { json(errorJson("BAD_CREDENTIALS", "Email/username or password is incorrect"), HttpStatusCode.Unauthorized) }
        api.unauthorized.test {
            val r = api.send(HttpMethod.Post, "auth/login", buildJsonObject { })
            assertEquals(AppError.Unauthenticated("BAD_CREDENTIALS", "Email/username or password is incorrect"), assertIs<Outcome.Err>(r).error)
            expectNoEvents()
        }
    }

    @Test fun expiredSessionBroadcastsUnauthorized() = runTest {
        server.routes["GET /api/auth/me"] = { json(errorJson("UNAUTHENTICATED", "Please sign in"), HttpStatusCode.Unauthorized) }
        api.unauthorized.test {
            api.send(HttpMethod.Get, "auth/me")
            awaitItem()
        }
    }

    @Test fun validationIssuesAreMapped() = runTest {
        server.routes["POST /api/auth/signup"] = {
            json("""{"error":{"code":"BAD_REQUEST","message":"Enter a valid email","issues":[{"path":"email","message":"Enter a valid email"}]}}""", HttpStatusCode.BadRequest)
        }
        val err = assertIs<Outcome.Err>(api.send(HttpMethod.Post, "auth/signup", buildJsonObject { })).error
        assertEquals(AppError.Validation(listOf(FieldIssue("email", "Enter a valid email")), "Enter a valid email"), err)
    }

    @Test fun serverAndNetworkFailures() = runTest {
        server.routes["GET /api/tasks/bootstrap"] = { json(errorJson("INTERNAL", "Something went wrong"), HttpStatusCode.InternalServerError) }
        assertEquals(AppError.Server(500, "INTERNAL", "Something went wrong"), assertIs<Outcome.Err>(api.send(HttpMethod.Get, "tasks/bootstrap")).error)
        server.offline = true
        assertEquals(AppError.Offline, assertIs<Outcome.Err>(api.send(HttpMethod.Get, "tasks/bootstrap")).error)
    }

    @Test fun nonJsonErrorBodyStillMaps() {
        assertEquals(AppError.Server(502, "ERROR", "Request failed (502)"), ApiClient.toAppError(502, null))
    }

    @Test fun connectionDroppedMidBodyIsOffline() = runTest {
        val engine = MockEngine {
            val channel = ByteChannel()
            channel.writeStringUtf8("{\"user\"")
            channel.flush()
            channel.close(RuntimeException("connection reset"))
            respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val flaky = ApiClient(engine, TEST_BASE, cookies)
        assertEquals(AppError.Offline, assertIs<Outcome.Err>(flaky.send(HttpMethod.Get, "auth/me")).error)
    }
}
