package tech.beyondscale.mitra.data.local

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SharedFlowsTest {
    @Test
    fun twoCollectorsShareOneUpstream() = runTest {
        var count = 0
        val shared = flow { count++; emit(listOf("a")) }.shareForRepository(backgroundScope)
        val got = mutableListOf<List<String>>()
        val j1 = launch { got += shared.first() }
        val j2 = launch { got += shared.first() }
        runCurrent()
        j1.join(); j2.join()
        assertEquals(1, count)
        assertEquals(listOf(listOf("a"), listOf("a")), got)
    }

    @Test
    fun resubscribeAfterStopDoesNotReplayStaleValue() = runTest {
        var value = "A"
        val shared = flow { emit(value); kotlinx.coroutines.awaitCancellation() }.shareForRepository(backgroundScope)
        var seen: String? = null
        val j: Job = launch { shared.collect { seen = it } }
        runCurrent()
        assertEquals("A", seen)
        j.cancel()
        advanceTimeBy(SHARE_STOP_TIMEOUT_MS + 1)
        runCurrent()
        value = "B"
        val first = mutableListOf<String>()
        val j2 = launch { shared.collect { first += it } }
        runCurrent()
        j2.cancel()
        assertEquals(listOf("B"), first)
    }

    @Test
    fun resubscribeWithinTimeoutKeepsUpstream() = runTest {
        var count = 0
        val shared = flow { count++; emit("A"); kotlinx.coroutines.awaitCancellation() }.shareForRepository(backgroundScope)
        val j = launch { shared.collect { } }
        runCurrent()
        j.cancel()
        advanceTimeBy(4_000)
        val j2 = launch { shared.collect { } }
        runCurrent()
        j2.cancel()
        assertEquals(1, count)
    }
}
