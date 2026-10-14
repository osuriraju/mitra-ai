package tech.beyondscale.mitra.feature.wellness

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.feature.habits.hdHabit
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.WellnessMetric
import tech.beyondscale.mitra.domain.model.WellnessSettings
import tech.beyondscale.mitra.domain.model.WellnessSettingsChange
import tech.beyondscale.mitra.domain.model.WellnessTrack
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FakeNotesRepository
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FakeWellnessRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WellnessViewModelsTest : ViewModelTest() {
    // 00:15 on Wed 14 Oct in India (still the 13th in UTC).
    private val clock = FixedClock(Instant.parse("2026-10-13T18:45:00Z"), TimeZone.of("Asia/Kolkata"))
    private val today = "2026-10-14"

    private fun TestScope.collect(flow: kotlinx.coroutines.flow.Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
        runCurrent()
    }

    private fun <T> TestScope.events(flow: kotlinx.coroutines.flow.Flow<T>): List<T> {
        val out = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(out) }
        return out
    }

    /* ---------- check-in ---------- */

    @Test fun checkinSeedsFromTodayAndDerivesHints() = runTest {
        val repo = FakeWellnessRepository(
            mapOf(
                "2026-10-12" to WellnessEntry("2026-10-12", mood = 2, weight = 72.0, savedAt = "2026-10-12"),
                "2026-10-13" to WellnessEntry("2026-10-13", mood = 3, savedAt = "2026-10-13"),
                today to WellnessEntry(today, mood = 4, sleepStart = "23:00", sleepEnd = "06:00", weight = 72.5, savedAt = today),
            ),
        )
        val vm = CheckinViewModel(repo, clock)
        collect(vm.state)
        val s = vm.state.value
        assertEquals(4, s.form.mood)
        assertEquals("7h 00m", s.sleepLabel)
        assertEquals("Target 7h 30m · 0h 30m under", s.sleepHint)
        assertEquals("Streak 3 days", s.streak)
        assertEquals("Saved for today — edit any time", s.heading)
        assertEquals("Last 72 kg · +0.5 kg", s.weightHint)
        assertEquals("25.1", s.bmiLabel) // 72.5 / 1.7²
        assertEquals("Over", s.bmiBand)
        assertEquals(2, s.bmiSegment)
        assertEquals("Logged Today", s.moodSub)
    }

    @Test fun checkinSavesOnlyChangedFieldsThenCloses() = runTest {
        val repo = FakeWellnessRepository(mapOf(today to WellnessEntry(today, mood = 2, sleepStart = "23:30", sleepEnd = "06:30", water = 3, savedAt = today)))
        val vm = CheckinViewModel(repo, clock)
        collect(vm.state)
        val ev = events(vm.events)
        vm.onAction(CheckinAction.Mood(3))
        vm.onAction(CheckinAction.Steps("7a200"))
        vm.onAction(CheckinAction.Water(25)) // capped
        vm.onAction(CheckinAction.Height("180"))
        vm.onAction(CheckinAction.Save)
        runCurrent()
        assertEquals(listOf("save:$today", "settings"), repo.calls)
        val e = repo.entryFlow.value.getValue(today)
        assertEquals(3, e.mood)
        assertEquals(7200, e.steps)
        assertEquals(MAX_WATER, e.water)
        assertEquals(180, repo.settingsFlow.value.height)
        assertTrue(ev.any { it is UiEvent.Snack && it.text == "Check-in saved" })
        assertEquals(UiEvent.Close, ev.last())
    }

    @Test fun checkinSkipsUntrackedMetricsAndRejectsBadWeight() = runTest {
        val repo = FakeWellnessRepository(settings = WellnessSettings(track = WellnessTrack(sleep = false, bmi = true)))
        val vm = CheckinViewModel(repo, clock)
        collect(vm.state)
        val ev = events(vm.events)
        vm.onAction(CheckinAction.Weight("0.5"))
        vm.onAction(CheckinAction.Save)
        runCurrent()
        assertTrue(repo.calls.isEmpty())
        assertTrue(ev.any { it is UiEvent.Snack && it.text.startsWith("Weight") })

        vm.onAction(CheckinAction.Weight("71.25"))
        assertEquals("71.2", vm.state.value.form.weight)
        vm.onAction(CheckinAction.Save)
        runCurrent()
        val e = repo.entryFlow.value.getValue(today)
        assertNull(e.sleepStart) // sleep isn't tracked → never written
        assertEquals(71.2, e.weight)
        assertEquals(0, e.water)
    }

    @Test fun skipSendsSnackOnly() = runTest {
        val repo = FakeWellnessRepository()
        val vm = CheckinViewModel(repo, clock)
        collect(vm.state)
        val ev = events(vm.events)
        vm.onAction(CheckinAction.Skip)
        assertEquals(listOf<UiEvent<Nothing>>(UiEvent.Snack("Skipped — no data recorded")), ev)
        assertTrue(repo.calls.isEmpty())
    }

    /* ---------- trends ---------- */

    @Test fun trendsRangesAndCharts() = runTest {
        val entries = (0 until 10).associate { i ->
            val d = tech.beyondscale.mitra.core.addDays(today, -i)
            d to WellnessEntry(d, mood = 3, sleepStart = "23:00", sleepEnd = if (i % 2 == 0) "07:00" else "05:00", steps = 9000, weight = 70.0 + i, savedAt = d)
        }
        val tasks = FakeTasksRepository(listOf(Task("t1", "Done", done = true, completedAt = today)))
        val vm = WellnessTrendsViewModel(FakeWellnessRepository(entries), tasks, clock)
        collect(vm.state)
        var s = vm.state.value
        assertEquals(30, s.days)
        assertEquals("Avg sleep", s.kpis[0].label)
        assertEquals("7h 00m", s.kpis[0].value)
        assertEquals("9,000", s.kpis[1].value)
        assertEquals(true, s.kpis[1].up)
        assertEquals("4.0 / 5", s.kpis[2].value)
        assertEquals(30, s.sleepValues!!.size)
        assertEquals(14, s.steps.size)
        assertEquals(listOf("1", "13", ""), listOf(s.stepsLabels[0], s.stepsLabels[12], s.stepsLabels[13]))
        assertEquals(listOf(79.0, 70.0), listOf(s.weights!!.first(), s.weights.last()))
        assertEquals(10, s.moodColumns)
        assertEquals("Sleep · last 30 nights", s.sleepTitle)

        vm.setRange(0)
        runCurrent()
        s = vm.state.value
        assertEquals(7, s.days)
        assertEquals(7, s.moodHeat.size)
        assertEquals(7, s.moodColumns)
        assertEquals(7, s.steps.size)
        assertEquals("Weight & BMI · week", s.weightTitle)
    }

    @Test fun trendsEmptyStates() = runTest {
        val vm = WellnessTrendsViewModel(FakeWellnessRepository(), FakeTasksRepository(), clock)
        collect(vm.state)
        val s = vm.state.value
        assertNull(s.sleepValues)
        assertEquals("Log 7 more check-ins to see this chart.", s.sleepEmpty)
        assertNull(s.weights)
        assertEquals("—", s.kpis[2].value)
        assertEquals("Not enough sleep variation yet to see a pattern.", s.insight)
    }

    /* ---------- sleep detail ---------- */

    @Test fun sleepDetailNavigatesAndSaves() = runTest {
        val repo = FakeWellnessRepository(
            mapOf("2026-10-13" to WellnessEntry("2026-10-13", mood = 1, sleepStart = "23:30", sleepEnd = "06:45", note = "Late tea", savedAt = "2026-10-13")),
        )
        val habits = FakeHabitsRepository(listOf(hdHabit(id = "h1", name = "Sleep by 11", logs = mapOf("2026-10-13" to 1, "2026-10-01" to 1))))
        val notes = FakeNotesRepository(listOf(Note("n1", "Journal", body = "Slept badly after the late meeting ran long", journalDate = "2026-10-13")))
        val vm = SleepDetailViewModel(repo, habits, notes, clock)
        collect(vm.state)
        var s = vm.state.value
        assertEquals("2026-10-13", s.day)
        assertEquals("Last night · Tue 13 Oct", s.eyebrow)
        assertEquals("7h 15m", s.duration)
        assertEquals("0h 15m under target", s.durationSub)
        assertEquals("11:30 PM", s.bed)
        assertEquals(7.3, s.week.last())
        assertEquals(listOf("W", "T", "F", "S", "S", "M", "T"), s.weekLabels)
        assertEquals(
            listOf("Sleep by 11", "Journal: Slept badly after the late meeting ran l…", "Mood 😕", "Late tea"),
            s.related.map { it.title },
        )
        assertEquals("Habit · 1 of last 3 done", s.related[0].sub)
        assertTrue(s.canNext)

        vm.onAction(SleepAction.Next)
        runCurrent()
        s = vm.state.value
        assertEquals(today, s.day)
        assertFalse(s.canNext)
        assertEquals("Not logged", s.duration)
        vm.onAction(SleepAction.Next) // can't go past today
        runCurrent()
        assertEquals(today, vm.state.value.day)

        vm.onAction(SleepAction.Prev)
        vm.onAction(SleepAction.Prev)
        vm.onAction(SleepAction.SaveTimes("22:45", "06:15"))
        vm.onAction(SleepAction.SaveNote("  "))
        runCurrent()
        val e = repo.entryFlow.value.getValue("2026-10-12")
        assertEquals("22:45", e.sleepStart)
        assertNull(e.note)
        assertEquals(listOf("save:2026-10-12", "save:2026-10-12"), repo.calls)
    }

    /* ---------- settings ---------- */

    @Test fun settingsTogglesTargetsAndShare() = runTest {
        val repo = FakeWellnessRepository()
        val shared = mutableListOf<Boolean>()
        val vm = WellnessSettingsViewModel(repo) { shared += it }
        collect(vm.state)
        val ev = events(vm.events)
        assertEquals("7h 30m", vm.state.value.sleepTarget)
        assertEquals("8,000", vm.state.value.stepsTarget)

        vm.onAction(WellnessSettingsAction.Track(WellnessMetric.Water, false))
        vm.onAction(WellnessSettingsAction.SetTarget(WellnessTarget.Sleep, "8:15"))
        vm.onAction(WellnessSettingsAction.SetTarget(WellnessTarget.Steps, "10,000"))
        vm.onAction(WellnessSettingsAction.SetTarget(WellnessTarget.Water, "50")) // out of range
        vm.onAction(WellnessSettingsAction.ShareAI(true))
        vm.onAction(WellnessSettingsAction.EveningReminder("22:30"))
        runCurrent()
        val s = repo.settingsFlow.value
        assertFalse(s.track.water)
        assertEquals(495, s.sleepTarget)
        assertEquals(10_000, s.stepsTarget)
        assertEquals(8, s.waterTarget)
        assertTrue(s.shareAI)
        assertEquals("22:30", s.eveningReminder)
        assertEquals(listOf(true), shared)
        assertTrue(ev.any { it is UiEvent.Snack && it.text.startsWith("Water target") })
        assertEquals("8h 15m", vm.state.value.sleepTarget)
    }

    @Test fun parseTargetLimits() {
        assertEquals(WellnessSettingsChange.SleepTarget(450), parseTarget(WellnessTarget.Sleep, "7.5"))
        assertEquals(WellnessSettingsChange.SleepTarget(420), parseTarget(WellnessTarget.Sleep, "7:"))
        assertNull(parseTarget(WellnessTarget.Sleep, "0:30"))
        assertNull(parseTarget(WellnessTarget.Sleep, "7:75"))
        assertNull(parseTarget(WellnessTarget.Height, "300"))
        assertEquals(WellnessSettingsChange.Height(165), parseTarget(WellnessTarget.Height, "165"))
        assertEquals("8:05", targetInput(WellnessSettings(sleepTarget = 485), WellnessTarget.Sleep))
    }
}
