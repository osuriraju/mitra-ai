package tech.beyondscale.mitra.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PeriodicSyncControllerTest {
    @Test fun enablesOnlyWhilePending() = runTest {
        val pending = MutableStateFlow(0L)
        val calls = mutableListOf<Boolean>()
        PeriodicSyncController(pending, { calls += it }, backgroundScope)
        runCurrent()
        for (n in listOf(2L, 3L, 0L)) { pending.value = n; runCurrent() }
        assertEquals(listOf(false, true, false), calls)
    }
}
