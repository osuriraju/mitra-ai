package tech.beyondscale.mitra.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.FieldIssue
import tech.beyondscale.mitra.core.Outcome

/** Thin Ktor wrapper: every failure becomes an [AppError], mirroring mitra-frontend/src/lib/api.ts. */
class ApiClient(engine: HttpClientEngine, baseUrl: String, cookies: CookiesStorage) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val base = baseUrl.trimEnd('/') + "/"
    private val _unauthorized = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when the server says the session is gone (not on a wrong password at login). */
    val unauthorized: SharedFlow<Unit> = _unauthorized.asSharedFlow()

    private val http = HttpClient(engine) {
        expectSuccess = false
        install(HttpCookies) { storage = cookies }
        install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000 }
    }

    /** [path] is relative to the API base with no leading slash, e.g. "auth/login". */
    suspend fun send(method: HttpMethod, path: String, body: JsonElement? = null): Outcome<JsonElement?> {
        val response: HttpResponse
        val text: String
        try {
            response = http.request(base + path) {
                this.method = method
                if (body != null) setBody(TextContent(json.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json))
            }
            text = response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Err(AppError.Offline)
        }
        val element = if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (response.status.isSuccess()) return Outcome.Ok(element)
        val error = toAppError(response.status.value, element)
        if (error is AppError.Unauthenticated && error.code == "UNAUTHENTICATED") _unauthorized.tryEmit(Unit)
        return Outcome.Err(error)
    }

    suspend inline fun <reified T> call(method: HttpMethod, path: String, body: JsonElement? = null): Outcome<T> =
        when (val r = send(method, path, body)) {
            is Outcome.Err -> r
            is Outcome.Ok -> try {
                Outcome.Ok(json.decodeFromJsonElement<T>(r.value ?: JsonNull))
            } catch (e: IllegalArgumentException) { // includes SerializationException
                Outcome.Err(AppError.Unknown(e))
            }
        }

    companion object {
        /** Parses `{ error: { code, message, issues? } }` (mitra-backend/src/common/http-exception.filter.ts). */
        fun toAppError(status: Int, element: JsonElement?): AppError {
            val err = (element as? JsonObject)?.get("error") as? JsonObject
            val code = (err?.get("code") as? JsonPrimitive)?.contentOrNull ?: "ERROR"
            val message = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Request failed ($status)"
            val issues = (err?.get("issues") as? JsonArray)?.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                FieldIssue(
                    (o["path"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (o["message"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
            }
            return when {
                status == 401 -> AppError.Unauthenticated(code, message)
                status == 400 && !issues.isNullOrEmpty() -> AppError.Validation(issues, message)
                else -> AppError.Server(status, code, message)
            }
        }
    }
}
