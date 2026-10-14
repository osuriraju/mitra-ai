package tech.beyondscale.mitra.feature.habits

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HabitHistoryViewModelTest : ViewModelTest() {
    // 00:15 on Wed 14 Oct in India (still the 13th in UTC).
    private val clock = FixedClock(Instant.parse("2026-10-13T18:45:00Z"), TimeZone.of("Asia/Kolkata"))
    private val mwf = listOf(true, false, true, false, true, false, false)

    private fun TestScope.start(repo: FakeHabitsRepository, id: String = "read"): HabitHistoryViewModel {
        val vm = HabitHistoryViewModel(id, repo, clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test fun monthStatsAndFutureDaysDisabled() = runTest {
        // Daily count habit (target 2): October 1–10 done, a partial on the 13th.
        val logs = hdLogged("2026-10-01", "2026-10-10", 2) + mapOf("2026-10-13" to 1)
        val repo = FakeHabitsRepository(listOf(hdHabit(type = HabitType.Count, target = 2, unit = "glasses", logs = logs)))
        val vm = start(repo)
        val s = vm.state.value
        assertEquals("October 2026", s.monthTitle)
        assertEquals("71%", s.completion) // 10 of 14 due days ≤ today (the 15th–31st don't count)
        assertEquals("Oct", s.completionMonth)
        assertEquals(31 + 3, s.cells.size) // 1 Oct 2026 is a Thursday → 3 leading cells

        val future = s.styles.getValue("2026-10-20")
        assertTrue(future.future)
        assertFalse(future.miss)
        val partial = s.styles.getValue("2026-10-13")
        assertTrue(partial.partial)
        assertFalse(partial.hit)
        assertTrue(s.styles.getValue("2026-10-11").miss)
        assertTrue(s.styles.getValue("2026-10-05").hit)
        assertTrue(s.styles.getValue(HD_TODAY).today)

        vm.onAction(HabitHistoryAction.Tap("2026-10-20"))
        runCurrent()
        assertTrue(repo.calls.isEmpty())
        vm.onAction(HabitHistoryAction.Tap("2026-10-13")) // partial → done
        runCurrent()
        assertEquals("log:read:2026-10-13:2", repo.calls.last())
        vm.onAction(HabitHistoryAction.Tap("2026-10-13")) // done → cleared
        runCurrent()
        assertEquals("log:read:2026-10-13:null", repo.calls.last())
        vm.onAction(HabitHistoryAction.Tap(HD_TODAY)) // today is editable
        runCurrent()
        assertEquals("log:read:2026-10-14:2", repo.calls.last())
    }

    @Test fun restDaysAreMarkedAndNeverMissed() = runTest {
        val vm = start(FakeHabitsRepository(listOf(hdHabit(days = mwf))))
        val sun = vm.state.value.styles.getValue("2026-10-11")
        assertTrue(sun.rest)
        assertFalse(sun.miss)
        assertTrue(vm.state.value.styles.getValue("2026-10-12").miss) // Monday, due, nothing logged
    }

    @Test fun bestMonth() = runTest {
        // Mon/Wed/Fri: September has 13 due days (12 done → 92%), October 6 up to the 14th (5 done → 83%).
        val sep = listOf("02", "04", "07", "09", "11", "14", "16", "18", "21", "23", "25", "28").map { "2026-09-$it" to 1 }
        val oct = listOf("02", "05", "07", "09", "12").map { "2026-10-$it" to 1 }
        val vm = start(FakeHabitsRepository(listOf(hdHabit(days = mwf, logs = (oct + sep).toMap()))))
        val s = vm.state.value
        assertEquals("Sep · 92%", s.bestMonth)
        assertEquals("83%", s.completion)
        assertEquals("17 days", s.total)
        assertEquals("Best streak", s.lastLabel)

        assertEquals("—", start(FakeHabitsRepository(listOf(hdHabit()))).state.value.bestMonth)
    }

    @Test fun monthSwitcherIsUnbounded() = runTest {
        val vm = start(FakeHabitsRepository(listOf(hdHabit(logs = mapOf("2026-09-30" to 1)))))
        vm.onAction(HabitHistoryAction.Next)
        runCurrent()
        assertEquals("November 2026", vm.state.value.monthTitle)
        assertTrue(vm.state.value.styles.getValue("2026-11-01").future)
        assertEquals("0%", vm.state.value.completion)
        vm.onAction(HabitHistoryAction.Prev)
        vm.onAction(HabitHistoryAction.Prev)
        runCurrent()
        assertEquals("September 2026", vm.state.value.monthTitle)
        assertEquals("3%", vm.state.value.completion) // 1 of 30
        assertEquals(listOf("30 Sep", "29 Sep"), vm.state.value.log.take(2).map { it.date })
        assertEquals(10, vm.state.value.log.size)
    }

    @Test fun logRowsAndDurationTotals() = runTest {
        val h = hdHabit(
            type = HabitType.Duration, target = 20, unit = "minutes", days = mwf,
            logs = mapOf(HD_TODAY to 20, "2026-10-13" to 5, "2026-10-12" to 300),
        )
        val s = start(FakeHabitsRepository(listOf(h))).state.value
        assertEquals(listOf("14 Oct", "13 Oct", "12 Oct", "11 Oct"), s.log.take(4).map { it.date })
        assertEquals(listOf("20 minutes", "5 minutes", "300 minutes", "—"), s.log.take(4).map { it.value })
        assertEquals(listOf("Done", "Partial", "Done", "Rest day"), s.log.take(4).map { it.status })
        assertEquals("Missed", s.log[5].status) // Fri 9 Oct
        assertEquals("2 sessions", s.total)
        assertEquals("Total time", s.lastLabel)
        assertEquals("5h 25m", s.lastValue)
        assertEquals("History · Read", s.title)
    }

    @Test fun notFound() = runTest {
        val s = start(FakeHabitsRepository(emptyList())).state.value
        assertTrue(s.loaded)
        assertTrue(s.notFound)
    }
}
