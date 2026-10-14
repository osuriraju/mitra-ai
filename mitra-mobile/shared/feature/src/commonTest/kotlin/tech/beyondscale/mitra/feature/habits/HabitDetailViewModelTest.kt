package tech.beyondscale.mitra.feature.habits

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.diffDays
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.ReviewEvery
import tech.beyondscale.mitra.domain.model.TimeOfDay
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeGoalsRepository
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

internal const val HD_TODAY = "2026-10-14" // a Wednesday

internal fun hdHabit(
    id: String = "read",
    name: String = "Read",
    type: HabitType = HabitType.Binary,
    target: Int = 1,
    unit: String = "",
    days: List<Boolean> = List(7) { true },
    logs: Map<String, Int> = emptyMap(),
    goalId: String? = null,
    archived: Boolean = false,
) = Habit(
    id = id, name = name, type = type, target = target, unit = unit, icon = "book", timeOfDay = TimeOfDay.Anytime,
    days = days, reminder = null, goalId = goalId, gentle = true, archived = archived, createdAt = "2026-01-01T00:00:00Z", logs = logs,
)

/** Days [from]..[to] (inclusive) logged with [value]. */
internal fun hdLogged(from: String, to: String, value: Int = 1): Map<String, Int> {
    val out = mutableMapOf<String, Int>()
    var d = from
    while (d <= to) { out[d] = value; d = addDays(d, 1) }
    return out
}

@OptIn(ExperimentalCoroutinesApi::class)
class HabitDetailViewModelTest : ViewModelTest() {
    // 18:45Z on Tue 13 Oct = 00:15 on Wed 14 Oct in India: the local day is already the 14th.
    private val clock = FixedClock(Instant.parse("2026-10-13T18:45:00Z"), TimeZone.of("Asia/Kolkata"))

    private class Harness(val vm: HabitDetailViewModel, val events: List<UiEvent<HabitDetailAction>>)

    private fun TestScope.start(habits: FakeHabitsRepository, goals: FakeGoalsRepository = FakeGoalsRepository(clock = clock), id: String = "read"): Harness {
        val vm = HabitDetailViewModel(id, habits, goals, clock)
        val events = mutableListOf<UiEvent<HabitDetailAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, events)
    }

    @Test fun kpisAndRingTone() = runTest {
        // Last 30 days = 15 Sep … 14 Oct. 12 hits in late September, 9 in October.
        val logs = hdLogged("2026-09-15", "2026-09-26") + hdLogged("2026-10-01", "2026-10-09")
        val s = start(FakeHabitsRepository(listOf(hdHabit(logs = logs)))).vm.state.value
        assertTrue(s.loaded)
        assertEquals("Read", s.name)
        assertEquals("Habit · yes / no · daily", s.eyebrow)
        assertEquals(70, s.ringPct)
        assertEquals(Tone.Success, s.ringTone)
        assertEquals("Last 30 days", s.lastLabel)
        assertEquals("21/30", s.lastValue)
        assertEquals("9 / 14", s.monthValue)
        assertEquals("0 days", s.streakValue) // today and yesterday open → no current streak
        assertEquals("12 days", s.bestValue)
    }

    @Test fun ringToneThresholds() {
        assertEquals(Tone.Success, ringToneFor(70))
        assertEquals(Tone.Warning, ringToneFor(69))
        assertEquals(Tone.Warning, ringToneFor(40))
        assertEquals(Tone.Danger, ringToneFor(39))
    }

    @Test fun measurableShowsAverageTrendAndEyebrow() = runTest {
        val h = hdHabit(
            id = "run", name = "Run", type = HabitType.Duration, target = 20, unit = "minutes",
            days = listOf(true, false, true, false, true, false, false),
            logs = mapOf("2026-10-12" to 20, "2026-10-14" to 15, "2026-10-09" to 0, "2026-09-01" to 10),
        )
        val s = start(FakeHabitsRepository(listOf(h)), id = "run").vm.state.value
        assertEquals("Habit · duration · 3× a week", s.eyebrow)
        assertEquals("Avg minutes", s.lastLabel)
        assertEquals("15", s.lastValue) // (20 + 15 + 10) / 3
        val trend = s.trend!!
        assertEquals(12, trend.values.size)
        assertEquals(17.5, trend.values.last()) // this week: 20 and 15
        assertEquals("minutes per session (weekly avg)", trend.legend)
        assertEquals("Target 20 minutes", trend.targetLabel)
        assertEquals(20.0, trend.goal)
        assertEquals(listOf(0, 4, 8), trend.labels.indices.filter { trend.labels[it].isNotEmpty() })
        assertEquals("Darker = more minutes", s.heatCaption)
    }

    @Test fun heatCellLevels() = runTest {
        // Wed 14 Oct is the last cell; Mon/Wed/Fri due. 12 Oct Mon (due, 0), 9 Oct Fri 5, 7 Oct Wed 20, 11 Oct Sun rest 0, 2 Oct Fri 15.
        val h = hdHabit(
            type = HabitType.Count, target = 20, unit = "pages",
            days = listOf(true, false, true, false, true, false, false),
            logs = mapOf("2026-10-09" to 5, "2026-10-07" to 20, "2026-10-02" to 15),
        )
        val s = start(FakeHabitsRepository(listOf(h))).vm.state.value
        assertEquals(84, s.heat.size)
        fun level(d: String) = s.heat[83 - diffDays(HD_TODAY, d)]
        assertEquals(0, level("2026-10-12"))
        assertEquals(1, level("2026-10-09"))
        assertEquals(4, level("2026-10-07"))
        assertEquals(0, level("2026-10-11"))
        assertEquals(2, level("2026-10-02")) // jsRound(15 / 20 × 3 = 2.25)
        assertEquals("Filled = done", habitDetailHeatCaption(hdHabit()))
    }

    @Test fun heatWeekColumnsReadOldestLeft() {
        val cells = List(84) { it }
        val grid = heatByWeekColumns(cells)
        assertEquals(84, grid.size)
        assertEquals(listOf(0, 7, 14, 21, 28, 35, 42, 49, 56, 63, 70, 77), grid.take(12)) // first row: day 1 of each week
        assertEquals(83, grid.last()) // bottom-right: today
    }

    @Test fun recentRowsAndPastToggle() = runTest {
        val repo = FakeHabitsRepository(listOf(hdHabit(logs = mapOf("2026-10-12" to 1))))
        val h = start(repo)
        val recent = h.vm.state.value.recent
        assertEquals(listOf("Today", "Yesterday", "Mon 12 Oct", "Sun 11 Oct", "Sat 10 Oct"), recent.map { it.label })
        assertEquals("Missed", recent[1].status)
        assertEquals("Done ✓", recent[2].status)
        assertNull(recent[0].chip) // today has no chip
        assertEquals("Mark done", recent[1].chip)
        assertEquals("Undo", recent[2].chip)

        h.vm.onAction(HabitDetailAction.ToggleDay("2026-10-13"))
        runCurrent()
        assertEquals("log:read:2026-10-13:1", repo.calls.last())
        h.vm.onAction(HabitDetailAction.ToggleDay("2026-10-12"))
        runCurrent()
        assertEquals("log:read:2026-10-12:null", repo.calls.last())
        h.vm.onAction(HabitDetailAction.ToggleDay("2026-10-20")) // future: ignored
        runCurrent()
        assertEquals("log:read:2026-10-12:null", repo.calls.last())
    }

    @Test fun recentMeasurableLabels() = runTest {
        val h = hdHabit(
            type = HabitType.Duration, target = 20, unit = "minutes", days = listOf(true, true, true, true, true, false, false),
            logs = mapOf(HD_TODAY to 20, "2026-10-13" to 5),
        )
        val recent = start(FakeHabitsRepository(listOf(h))).vm.state.value.recent
        assertEquals(listOf("20 minutes ✓", "5 minutes · partial", "Missed", "Rest day", "Rest day"), recent.map { it.status })
    }

    @Test fun doneTodayCompletesWithHapticAndUncheckAgain() = runTest {
        val repo = FakeHabitsRepository(listOf(hdHabit(logs = hdLogged("2026-10-08", "2026-10-13"))))
        val h = start(repo)
        h.vm.onAction(HabitDetailAction.DoneToday)
        runCurrent()
        assertEquals("log:read:2026-10-14:1", repo.calls.last())
        assertTrue(h.vm.state.value.doneToday)
        assertEquals(UiEvent.Haptic(HapticKind.Milestone), h.events[0]) // 6 → 7-day streak
        assertEquals(UiEvent.Snack<HabitDetailAction>("Read ✓", HabitDetailAction.SetLog(HD_TODAY, null)), h.events[1])

        h.vm.onAction(HabitDetailAction.DoneToday)
        runCurrent()
        assertEquals("log:read:2026-10-14:null", repo.calls.last())
        assertEquals(UiEvent.Snack<HabitDetailAction>("Unchecked"), h.events.last())
    }

    @Test fun logValueLogsToday() = runTest {
        val repo = FakeHabitsRepository(listOf(hdHabit(type = HabitType.Duration, target = 20, unit = "minutes")))
        val h = start(repo)
        h.vm.onAction(HabitDetailAction.LogValue(""))
        runCurrent()
        assertTrue(repo.calls.isEmpty())
        h.vm.onAction(HabitDetailAction.LogValue("5"))
        runCurrent()
        assertEquals("log:read:2026-10-14:5", repo.calls.last())
        assertEquals(UiEvent.Snack<HabitDetailAction>("Logged 5 minutes"), h.events.last())
    }

    @Test fun supportsGoalShowsProgressAndEvidence() = runTest {
        val goal = Goal(
            id = "g1", name = "Read 20 books", type = GoalType.Habit, term = GoalTerm.Medium, due = "2026-12-31", startedOn = "2026-01-01",
            current = 0.0, target = 100.0, unit = "", formula = null, nextAction = "", reviewEvery = ReviewEvery.Monthly,
            reviewOn = "2026-11-01", state = GoalState.Active, notes = "", createdAt = "", milestones = emptyList(), progressLog = emptyList(),
        )
        val logs = hdLogged("2026-09-15", "2026-09-26") + hdLogged("2026-10-01", "2026-10-09")
        val s = start(FakeHabitsRepository(listOf(hdHabit(logs = logs, goalId = "g1"))), FakeGoalsRepository(listOf(goal), clock)).vm.state.value
        val g = s.goal!!
        assertEquals("g1", g.id)
        assertEquals("Read 20 books", g.name)
        assertEquals(70, g.pct)
        assertEquals("Evidence: 21 sessions in 30 days", g.evidence)

        // An unknown goalId (goal deleted on the server) reads as "no goal".
        val orphan = start(FakeHabitsRepository(listOf(hdHabit(goalId = "gone")))).vm.state.value
        assertNull(orphan.goal)
    }

    @Test fun archiveTogglesWithUndo() = runTest {
        val repo = FakeHabitsRepository(listOf(hdHabit()))
        val h = start(repo)
        h.vm.onAction(HabitDetailAction.ToggleArchive)
        runCurrent()
        assertEquals("update:read", repo.calls.last())
        assertTrue(h.vm.state.value.archived)
        assertEquals(UiEvent.Snack<HabitDetailAction>("Habit archived · history kept", HabitDetailAction.SetArchived(false)), h.events.last())
        h.vm.onAction(HabitDetailAction.ToggleArchive)
        runCurrent()
        assertEquals(UiEvent.Snack<HabitDetailAction>("Habit restored", HabitDetailAction.SetArchived(true)), h.events.last())
    }

    @Test fun deleteConfirmsThenNavigatesBack() = runTest {
        val repo = FakeHabitsRepository(listOf(hdHabit()))
        val h = start(repo)
        h.vm.onAction(HabitDetailAction.Delete)
        runCurrent()
        assertEquals("delete:read", repo.calls.last())
        assertEquals(listOf(UiEvent.Snack<HabitDetailAction>("Habit deleted"), UiEvent.Close), h.events)
        // The screen is leaving: it never flashes "This habit doesn't exist".
        assertTrue(h.vm.state.value.closing)
        assertTrue(!h.vm.state.value.notFound)
    }

    @Test fun notFound() = runTest {
        val s = start(FakeHabitsRepository(emptyList())).vm.state.value
        assertTrue(s.loaded)
        assertTrue(s.notFound)
    }
}
