package tech.beyondscale.mitra.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.testDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tech.beyondscale.mitra.data.remote.UserDto
import app.cash.sqldelight.db.QueryResult

class UserCacheTest {
    private val driver = testDriver()
    private val db = MitraDb(driver)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test fun corruptCachedRowIsTreatedAsNoUser() = runTest {
        db.userQueries.upsert("u1", "{not json")
        val cache = UserCache(db, json, UnconfinedTestDispatcher(testScheduler))
        assertNull(cache.current())
        assertNull(cache.observe().first())
    }

    @Test fun putKeepsASingleRow() = runTest {
        val cache = UserCache(db, json, UnconfinedTestDispatcher(testScheduler))
        cache.put(UserDto("u1", "One", "one@example.com"))
        cache.put(UserDto("u2", "Two", "two@example.com"))
        val ids = driver.executeQuery(null, "SELECT id FROM cached_user", { c ->
            val out = mutableListOf<String>()
            while (c.next().value) out += c.getString(0)!!
            QueryResult.Value(out)
        }, 0).value
        assertEquals(listOf("u2"), ids)
    }
}
