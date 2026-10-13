package tech.beyondscale.mitra.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.testDriver
import kotlin.test.Test
import kotlin.test.assertNull

class UserCacheTest {
    private val db = MitraDb(testDriver())
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test fun corruptCachedRowIsTreatedAsNoUser() = runTest {
        db.userQueries.upsert("u1", "{not json")
        val cache = UserCache(db, json, UnconfinedTestDispatcher(testScheduler))
        assertNull(cache.current())
        assertNull(cache.observe().first())
    }
}
