package tech.beyondscale.mitra.data.remote

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

const val TEST_BASE = "http://test.local/api/"

fun userJson(id: String = "u1", name: String = "Raju Osuri", settings: String = "{}") =
    """{"user":{"id":"$id","name":"$name","email":"raju@example.com","username":null,"phone":null,"settings":$settings,"onboardedAt":null,"deletedAt":null,"memberSince":"2026-09-01T00:00:00.000Z"}}"""

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg extra: Pair<String, String>): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), *extra.map { it.first to listOf(it.second) }.toTypedArray()))

fun errorJson(code: String, message: String) = """{"error":{"code":"$code","message":"$message"}}"""

/** Routes requests by "METHOD /path"; `offline = true` makes every call throw like a dead network. */
class MockServer {
    var offline = false
    private val recorded = MutableStateFlow<List<HttpRequestData>>(emptyList())
    /** Thread-safe snapshot (Ktor's engine records from its own thread). */
    val requests: List<HttpRequestData> get() = recorded.value
    val routes = mutableMapOf<String, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()
    val engine = MockEngine { req ->
        if (offline) throw Exception("network down")
        recorded.update { it + req }
        val handler = routes["${req.method.value} ${req.url.encodedPath}"]
        handler?.invoke(this, req) ?: respond("", HttpStatusCode.NotFound)
    }
    val log: List<String> get() = requests.map { "${it.method.value} ${it.url.encodedPath}" }
}
