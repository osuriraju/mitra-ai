package tech.beyondscale.mitra.data.local

import app.cash.turbine.test
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.data.testDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalStoreTest {
    private val db = MitraDb(testDriver())

    @Test fun prefStoreRoundTripsAndObserves() = runTest {
        val prefs = PrefStore(db, UnconfinedTestDispatcher(testScheduler))
        prefs.observe(PrefKeys.THEME).test {
            assertNull(awaitItem())
            prefs.put(PrefKeys.THEME, "Light")
            assertEquals("Light", awaitItem())
            prefs.remove(PrefKeys.THEME)
            assertNull(awaitItem())
        }
    }

    @Test fun clearUserDataKeepsDevicePrefs() {
        val prefs = PrefStore(db, UnconfinedTestDispatcher())
        prefs.put(PrefKeys.THEME, "Light")
        db.userQueries.upsert("u1", "{}")
        db.outboxQueries.insert("o1", "user-settings", "PATCH", "users/me/settings", "{}", null, 0)

        LocalData(db).clearUserData()

        assertNull(db.userQueries.selectCurrent().executeAsOneOrNull())
        assertEquals(0L, db.outboxQueries.count().executeAsOne())
        assertEquals("Light", prefs.get(PrefKeys.THEME))
    }
}
