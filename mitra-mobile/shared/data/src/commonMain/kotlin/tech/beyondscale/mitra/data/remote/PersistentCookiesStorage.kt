package tech.beyondscale.mitra.data.remote

import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tech.beyondscale.mitra.core.AppClock

@Serializable
private data class StoredCookie(
    val name: String,
    val value: String,
    val expiresAt: Long? = null,
    val path: String? = null,
    val domain: String? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val encoding: String = CookieEncoding.URI_ENCODING.name,
)

/**
 * Keeps the backend's session cookie across app restarts. We talk to a single API host,
 * so every stored cookie is sent with every request.
 */
class PersistentCookiesStorage(private val store: SecureStore, private val clock: AppClock) : CookiesStorage {
    private val mutex = Mutex()
    private var cache: MutableMap<String, StoredCookie>? = null

    private fun cookies(): MutableMap<String, StoredCookie> = cache ?: run {
        val saved = store.get(KEY)?.let { runCatching { Json.decodeFromString<List<StoredCookie>>(it) }.getOrNull() }.orEmpty()
        saved.associateBy { it.name }.toMutableMap().also { cache = it }
    }

    private fun persist() = store.put(KEY, Json.encodeToString(cookies().values.toList()))

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        val now = clock.now().toEpochMilliseconds()
        val jar = cookies()
        if (jar.values.removeAll { it.expiresAt != null && it.expiresAt <= now }) persist()
        jar.values.map { Cookie(it.name, it.value, encoding = runCatching { CookieEncoding.valueOf(it.encoding) }.getOrDefault(CookieEncoding.URI_ENCODING), path = it.path, domain = it.domain, secure = it.secure, httpOnly = it.httpOnly) }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie): Unit = mutex.withLock {
        val now = clock.now().toEpochMilliseconds()
        val expiresAt = cookie.maxAge?.takeIf { it > 0 }?.let { now + it * 1000L } ?: cookie.expires?.timestamp
        val jar = cookies()
        if (cookie.value.isEmpty() || (expiresAt != null && expiresAt <= now)) {
            jar.remove(cookie.name)
        } else {
            jar[cookie.name] = StoredCookie(cookie.name, cookie.value, expiresAt, cookie.path, cookie.domain, cookie.secure, cookie.httpOnly, cookie.encoding.name)
        }
        persist()
    }

    suspend fun clear() = mutex.withLock { cookies().clear(); persist() }

    override fun close() {}

    private companion object { const val KEY = "cookies" }
}
