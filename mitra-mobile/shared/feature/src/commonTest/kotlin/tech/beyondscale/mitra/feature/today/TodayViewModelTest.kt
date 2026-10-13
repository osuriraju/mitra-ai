package tech.beyondscale.mitra.feature.today

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import tech.beyondscale.mitra.testing.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TodayViewModelTest : ViewModelTest() {
    // 04:30Z = 10:00 in India
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"), TimeZone.of("Asia/Kolkata"))

    @Test fun greetsByFirstNameInLocalTime() {
        val vm = TodayViewModel(FakeAuthRepository(SessionState.SignedIn(testUser())), clock)
        assertEquals("Good morning, Raju", vm.state.value.greeting)
        assertEquals("Tuesday, 13 October 2026 · 10:00 AM", vm.state.value.dateLine)
        assertEquals(listOf("Tasks", "Habits", "Spent today", "Sleep"), vm.state.value.stats.map { it.label })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun headerRefreshesAsTheClockAdvances() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = TodayViewModel(FakeAuthRepository(SessionState.SignedIn(testUser())), clock)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals("Good morning, Raju", vm.state.value.greeting)

        clock.instant = Instant.parse("2026-10-13T06:30:00Z") // 12:00 IST
        advanceTimeBy(60_001)
        assertEquals("Good afternoon, Raju", vm.state.value.greeting)
        assertEquals("Tuesday, 13 October 2026 · 12:00 PM", vm.state.value.dateLine)
    }
}
