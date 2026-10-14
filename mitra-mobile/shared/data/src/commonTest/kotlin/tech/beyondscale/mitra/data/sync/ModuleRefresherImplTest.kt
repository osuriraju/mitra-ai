package tech.beyondscale.mitra.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ModuleRefresherImplTest {
    private val start = Instant.parse("2026-10-14T04:30:00Z")
    private val clock = FixedClock(start)
    private val session = MutableStateFlow<SessionState>(SessionState.SignedOut)
    private val drained = MutableSharedFlow<Unit>()
    private val calls = mutableListOf<String>()

    private fun TestScope.refresher(
        modules: Map<String, suspend () -> Unit> = mapOf("user" to { calls += "user" }, "tasks" to { calls += "tasks" }),
    ) = ModuleRefresherImpl(session, drained, clock, backgroundScope, modules).also { runCurrent() }

    private fun TestScope.at(seconds: Int) { clock.instant = start + seconds.seconds; runCurrent() }

    @Test fun signInRefreshesEveryModuleExceptUserOnce() = runTest {
        refresher()
        session.value = SessionState.SignedIn(testUser())
        runCurrent()
        assertEquals(listOf("tasks"), calls)
        session.value = SessionState.SignedIn(testUser(name = "Renamed")) // same account, new user object
        runCurrent()
        assertEquals(listOf("tasks"), calls)
    }

    @Test fun foregroundRefreshesOnlyStaleModules() = runTest {
        val r = refresher()
        session.value = SessionState.SignedIn(testUser())
        runCurrent()
        calls.clear()
        at(30); r.onForeground(); runCurrent()
        assertEquals(emptyList(), calls)
        at(61); r.onForeground(); runCurrent()
        assertEquals(listOf("tasks", "user"), calls.sorted())
    }

    @Test fun signedOutForegroundDoesNothing() = runTest {
        val r = refresher()
        r.onForeground(); runCurrent()
        drained.emit(Unit); runCurrent()
        assertEquals(emptyList(), calls)
    }

    @Test fun drainedRefreshesStaleModulesButNotUser() = runTest {
        refresher()
        session.value = SessionState.SignedIn(testUser())
        runCurrent()
        calls.clear()
        at(61)
        drained.emit(Unit); runCurrent()
        assertEquals(listOf("tasks"), calls)
    }

    @Test fun drainedWithinTheThrottleRefreshesOnceTheWindowEnds() = runTest {
        refresher()
        session.value = SessionState.SignedIn(testUser())
        runCurrent()
        calls.clear()
        at(10)
        drained.emit(Unit); runCurrent()
        drained.emit(Unit); runCurrent() // a second drain in the same window adds nothing
        assertEquals(emptyList(), calls)
        clock.instant = start + 60.seconds
        advanceTimeBy(50_001); runCurrent()
        assertEquals(listOf("tasks"), calls)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(listOf("tasks"), calls)
    }

    @Test fun deferredRefreshIsNotPostponedByABackwardClockJump() = runTest {
        refresher()
        session.value = SessionState.SignedIn(testUser())
        runCurrent()
        calls.clear()
        at(-1_000) // wall clock jumps back 1000s
        drained.emit(Unit); runCurrent()
        assertEquals(emptyList(), calls)
        advanceTimeBy(60_001); runCurrent()
        assertEquals(listOf("tasks"), calls)
    }

    @Test fun signOutCancelsADeferredRefresh() = runTest {
        refresher()
        session.value = SessionState.SignedIn(testUser())
        runCurrent()
        calls.clear()
        at(10)
        drained.emit(Unit); runCurrent()
        session.value = SessionState.SignedOut
        runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(emptyList(), calls)
    }

    @Test fun newAccountResetsThrottle() = runTest {
        refresher()
        session.value = SessionState.SignedIn(testUser())
        runCurrent()
        at(5)
        session.value = SessionState.SignedIn(testUser(id = "u2"))
        runCurrent()
        assertEquals(listOf("tasks", "tasks"), calls)
    }

    @Test fun signingBackInAfterSignOutRefreshesAgain() = runTest {
        refresher()
        session.value = SessionState.SignedIn(testUser())
        runCurrent()
        session.value = SessionState.SignedOut // logout cleared the database
        runCurrent()
        at(5)
        session.value = SessionState.SignedIn(testUser())
        runCurrent()
        assertEquals(listOf("tasks", "tasks"), calls)
    }

    @Test fun failingModuleDoesNotStopOthers() = runTest {
        refresher(
            mapOf(
                "user" to { calls += "user" },
                "tasks" to { calls += "tasks"; error("boom") },
                "habits" to { calls += "habits" },
            ),
        )
        session.value = SessionState.SignedIn(testUser())
        runCurrent()
        assertEquals(listOf("habits", "tasks"), calls.sorted())
    }
}
