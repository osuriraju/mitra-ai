package tech.beyondscale.mitra.data.remote

import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.testing.FixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class PersistentCookiesStorageTest {
    private val url = Url(TEST_BASE)
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"))
    private val store = InMemorySecureStore()

    @Test fun survivesProcessRestart() = runTest {
        PersistentCookiesStorage(store, clock).addCookie(url, Cookie("mitra_sid", "abc"))
        val reloaded = PersistentCookiesStorage(store, clock).get(url)
        assertEquals(listOf("mitra_sid" to "abc"), reloaded.map { it.name to it.value })
    }

    @Test fun expiredOrClearedCookieIsRemoved() = runTest {
        val jar = PersistentCookiesStorage(store, clock)
        jar.addCookie(url, Cookie("mitra_sid", "abc"))
        jar.addCookie(url, Cookie("mitra_sid", "", expires = GMTDate(0)))
        assertTrue(jar.get(url).isEmpty())
    }

    @Test fun cookieWithMaxAgeExpires() = runTest {
        val jar = PersistentCookiesStorage(store, clock)
        jar.addCookie(url, Cookie("mitra_sid", "abc", maxAge = 60))
        clock.instant = Instant.parse("2026-10-13T04:31:01Z")
        assertTrue(jar.get(url).isEmpty())
    }

    @Test fun corruptStoredValueIsIgnored() = runTest {
        store.put("cookies", "{not json")
        assertTrue(PersistentCookiesStorage(store, clock).get(url).isEmpty())
    }

    @Test fun clearRemovesEverything() = runTest {
        val jar = PersistentCookiesStorage(store, clock)
        jar.addCookie(url, Cookie("mitra_sid", "abc"))
        jar.clear()
        assertTrue(PersistentCookiesStorage(store, clock).get(url).isEmpty())
    }
}
