package tech.beyondscale.mitra.feature.habits

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.TimeOfDay
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HabitsViewModelTest : ViewModelTest() {
    // 04:30Z = 10:00 on Wed 14 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-14T04:30:00Z"), TimeZone.of("Asia/Kolkata"))

    private class Harness(val vm: HabitsViewModel, val repo: FakeHabitsRepository, val events: List<UiEvent<HabitsAction>>) {
        val state get() = vm.state.value
        fun habit(id: String) = repo.habitFlow.value.first { it.id == id }
    }

    private fun TestScope.start(vararg habits: Habit): Harness {
        val repo = FakeHabitsRepository(habits.toList())
        val vm = HabitsViewModel(repo, clock)
        val events = mutableListOf<UiEvent<HabitsAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, repo, events)
    }

    @Test fun headerStats() = runTest {
        val h = start(
            testHabit("meditate", logs = logRun(12)), // done today, 12-day streak, 100 % this week
            testHabit("journal"), // 0 %
            testHabit("walk", logs = logRun(5, daysAgoEnd = 2)), // 5 of the last 7 days → 71 %, today open
        )
        assertEquals("1 / 3", h.state.doneToday)
        assertEquals("57%", h.state.weekRate)
        assertEquals("12", h.state.longestStreak)
    }

    @Test fun todayGroupsOnlyDueHabits() = runTest {
        val weekend = listOf(false, false, false, false, false, true, true)
        val h = start(
            testHabit("yoga", timeOfDay = TimeOfDay.Evening, days = weekend),
            testHabit("run", timeOfDay = TimeOfDay.Morning),
            testHabit("read", timeOfDay = TimeOfDay.Evening),
            testHabit("old", archived = true),
        )
        assertEquals(listOf("Morning" to listOf("run"), "Evening" to listOf("read")), h.state.groups.map { g -> g.title to g.rows.map { it.id } })
        assertFalse(h.state.nothingToday)

        h.vm.onAction(HabitsAction.Tab(1))
        assertEquals(1, h.state.tab)
        assertEquals(listOf("Morning" to listOf("run"), "Evening" to listOf("yoga", "read")), h.state.groups.map { g -> g.title to g.rows.map { it.id } })
        // "This week" lists every non-archived habit with its dots and rate.
        assertEquals(listOf("yoga", "run", "read"), h.state.week.map { it.id })
        assertEquals(2, h.state.todayIndex)
    }

    @Test fun nothingScheduledTodayAndEmpty() = runTest {
        val h = start(testHabit("yoga", days = listOf(false, false, false, false, false, true, true)))
        assertTrue(h.state.nothingToday)
        assertTrue(h.state.groups.isEmpty())
        assertFalse(h.state.empty)

        val none = start(testHabit("old", archived = true))
        assertTrue(none.state.empty)
    }

    @Test fun checkLogsTodayWithUndo() = runTest {
        val h = start(testHabit("read", logs = logRun(3, daysAgoEnd = 1)))
        h.vm.onAction(HabitsAction.Check("read"))
        assertTrue("log:read:2026-10-14:1" in h.repo.calls)
        assertEquals(UiEvent.Haptic(HapticKind.Confirm), h.events.first()) // before the write
        val snack = h.events.filterIsInstance<UiEvent.Snack<HabitsAction>>().single()
        assertEquals("Read ✓ · streak 4", snack.text)
        assertEquals(HabitsAction.SetLog("read", TODAY, null), snack.undo)
        assertTrue(h.state.groups.single().rows.single().done)

        h.vm.onAction(snack.undo!!)
        assertEquals("log:read:2026-10-14:null", h.repo.calls.last())
        assertFalse(h.state.groups.single().rows.single().done)
    }

    @Test fun measurableCheckStepsWithoutUndo() = runTest {
        val h = start(testHabit("stretch", type = HabitType.Duration, target = 20, unit = "minutes"))
        h.vm.onAction(HabitsAction.Check("stretch"))
        assertEquals("log:stretch:2026-10-14:10", h.repo.calls.last())
        assertTrue(h.events.isEmpty()) // half-way: no snack, no haptic
        h.vm.onAction(HabitsAction.Check("stretch"))
        assertEquals("log:stretch:2026-10-14:20", h.repo.calls.last())
        assertEquals(listOf<UiEvent<HabitsAction>>(UiEvent.Haptic(HapticKind.Confirm), UiEvent.Snack("Stretch ✓ · target reached")), h.events)
    }

    @Test fun streakMilestoneHaptic() = runTest {
        val h = start(testHabit("read", logs = logRun(6, daysAgoEnd = 1)))
        h.vm.onAction(HabitsAction.Check("read"))
        assertTrue(UiEvent.Haptic(HapticKind.Milestone) in h.events)
        assertFalse(UiEvent.Haptic(HapticKind.Confirm) in h.events)
    }

    @Test fun swipeRightCompletesWithUndoToPreviousValue() = runTest {
        val h = start(testHabit("water", type = HabitType.Count, target = 8, unit = "glasses", logs = mapOf(TODAY to 5)))
        h.vm.onAction(HabitsAction.Complete("water"))
        assertEquals("log:water:2026-10-14:8", h.repo.calls.last())
        val snack = h.events.filterIsInstance<UiEvent.Snack<HabitsAction>>().single()
        assertEquals("Water ✓", snack.text)
        assertEquals(HabitsAction.SetLog("water", TODAY, 5), snack.undo)
    }

    @Test fun swipeLeftArchivesWithUndo() = runTest {
        val h = start(testHabit("read"))
        h.vm.onAction(HabitsAction.Archive("read"))
        assertEquals("update:read", h.repo.calls.last())
        assertTrue(h.habit("read").archived)
        assertTrue(h.state.empty)
        val snack = h.events.filterIsInstance<UiEvent.Snack<HabitsAction>>().single()
        assertEquals("Habit archived · history kept", snack.text)
        assertEquals(HabitsAction.Unarchive("read"), snack.undo)

        h.vm.onAction(snack.undo!!)
        assertFalse(h.habit("read").archived)
        assertEquals(listOf("read"), h.state.groups.flatMap { g -> g.rows.map { it.id } })
    }

    @Test fun unknownIdIsIgnored() = runTest {
        val h = start(testHabit("read"))
        h.vm.onAction(HabitsAction.Check("gone"))
        h.vm.onAction(HabitsAction.Complete("gone"))
        assertTrue(h.repo.calls.none { it.startsWith("log:") })
        assertTrue(h.events.isEmpty())
    }

    @Test fun refreshErrorSnacksOfflineCopy() = runTest {
        val h = start(testHabit("read"))
        h.repo.refreshResult = Outcome.Err(AppError.Offline)
        h.vm.onAction(HabitsAction.Refresh)
        assertEquals("refresh", h.repo.calls.last())
        assertEquals(UiEvent.Snack<HabitsAction>("You're offline — showing habits saved on this phone"), h.events.last())
        assertFalse(h.state.refreshing)
    }

    @Test fun rollsOverAtLocalMidnight() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val night = FixedClock(Instant.parse("2026-10-31T18:29:00Z"), TimeZone.of("Asia/Kolkata")) // 23:59 IST
        val repo = FakeHabitsRepository(listOf(testHabit("read", logs = mapOf("2026-10-31" to 1))))
        val vm = HabitsViewModel(repo, night)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals("1 / 1", vm.state.value.doneToday)

        night.instant = Instant.parse("2026-10-31T18:30:30Z") // 00:00:30 IST on 1 Nov
        advanceTimeBy(60_002)
        assertEquals("0 / 1", vm.state.value.doneToday)
        vm.onAction(HabitsAction.Check("read"))
        runCurrent()
        assertEquals("log:read:2026-11-01:1", repo.calls.last())
    }
}
