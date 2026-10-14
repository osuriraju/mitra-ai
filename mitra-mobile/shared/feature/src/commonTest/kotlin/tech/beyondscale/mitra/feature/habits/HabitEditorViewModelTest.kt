package tech.beyondscale.mitra.feature.habits

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.GoalTerm
import tech.beyondscale.mitra.domain.model.GoalType
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitChange
import tech.beyondscale.mitra.domain.model.HabitType
import tech.beyondscale.mitra.domain.model.ReviewEvery
import tech.beyondscale.mitra.domain.model.TimeOfDay
import tech.beyondscale.mitra.domain.model.WEEKDAYS
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeGoalsRepository
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** [FakeHabitsRepository] plus the exact changes each update sent. */
internal class RecordingHabits(val fake: FakeHabitsRepository) : HabitsRepository by fake {
    val updates = mutableListOf<Pair<String, List<HabitChange>>>()
    override suspend fun update(id: String, changes: List<HabitChange>) {
        updates += id to changes
        fake.update(id, changes)
    }
}

internal fun testGoal(id: String, name: String, state: GoalState = GoalState.Active) = Goal(
    id = id, name = name, type = GoalType.Numeric, term = GoalTerm.Medium, due = "2027-03-31", startedOn = "2026-10-01",
    current = 0.0, target = 100.0, unit = "", formula = null, nextAction = "", reviewEvery = ReviewEvery.Monthly,
    reviewOn = "2026-11-01", state = state, notes = "", createdAt = "", milestones = emptyList(), progressLog = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HabitEditorViewModelTest : ViewModelTest() {
    private val clock = FixedClock(Instant.parse("2026-10-14T04:30:00Z"), TimeZone.of("Asia/Kolkata"))

    private class Harness(
        val vm: HabitEditorViewModel,
        val repo: RecordingHabits,
        val events: List<UiEvent<HabitEditorAction>>,
        val nav: List<HabitEditorEvent>,
    ) {
        val state get() = vm.state.value
        val form get() = vm.state.value.form
        val habits get() = repo.fake.habitFlow.value
        fun act(vararg a: HabitEditorAction) = a.forEach(vm::onAction)
    }

    private fun TestScope.start(habits: List<Habit> = emptyList(), goals: List<Goal> = emptyList()): Harness {
        val repo = RecordingHabits(FakeHabitsRepository(habits))
        val vm = HabitEditorViewModel(repo, FakeGoalsRepository(goals, clock), clock)
        val events = mutableListOf<UiEvent<HabitEditorAction>>()
        val nav = mutableListOf<HabitEditorEvent>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        backgroundScope.launch(d) { vm.navigation.toList(nav) }
        runCurrent()
        return Harness(vm, repo, events, nav)
    }

    @Test fun opensWithWebDefaults() = runTest {
        val h = start(goals = listOf(testGoal("g1", "Run a 10K"), testGoal("g2", "Old", GoalState.Completed)))
        h.act(HabitEditorAction.Open("Stretch"))
        val f = h.form
        assertTrue(f.open)
        assertEquals("New habit", h.state.heading)
        assertEquals("Create habit", h.state.saveLabel)
        assertEquals("Stretch", f.name)
        assertEquals(HabitType.Binary, f.type)
        assertEquals("repeat", f.icon)
        assertEquals(TimeOfDay.Anytime, f.timeOfDay)
        assertEquals("Every day", h.state.scheduleLabel)
        assertFalse(f.reminderOn)
        assertEquals("21:30", f.reminder)
        assertTrue(f.gentle)
        assertFalse(h.state.showTarget)
        // Only active goals are offered, after "None — optional".
        assertEquals(listOf(null to "None — optional", "g1" to "Run a 10K"), h.state.goals.map { it.id to it.label })
    }

    @Test fun nameAndUnitAreCappedLikeTheServer() = runTest {
        val h = start()
        h.act(HabitEditorAction.Open(), HabitEditorAction.Name("x".repeat(90)), HabitEditorAction.Unit("u".repeat(25)))
        assertEquals(80, h.form.name.length)
        assertEquals(20, h.form.unit.length)
    }

    @Test fun validationOrder() = runTest {
        val h = start()
        h.act(HabitEditorAction.Open(), HabitEditorAction.Name("  "), HabitEditorAction.Save)
        assertEquals("Name the habit", h.form.error)
        assertTrue(h.form.open)

        h.act(HabitEditorAction.Name("Read"))
        assertNull(h.form.error)
        repeat(7) { h.act(HabitEditorAction.ToggleDay(it)) }
        h.act(HabitEditorAction.Save)
        assertEquals("Pick at least one day", h.form.error)
        assertTrue(h.repo.fake.calls.isEmpty())
    }

    @Test fun createSavesAndNavigates() = runTest {
        val h = start(goals = listOf(testGoal("g1", "Run a 10K")))
        h.act(
            HabitEditorAction.Open(), HabitEditorAction.Name(" Evening stretch "), HabitEditorAction.Icon("activity"),
            HabitEditorAction.Type(HabitType.Duration), HabitEditorAction.Target("15"), HabitEditorAction.TimeOfDay(TimeOfDay.Evening),
            HabitEditorAction.Schedule("Weekdays"), HabitEditorAction.ReminderOn(true), HabitEditorAction.ReminderTime("07:15"),
            HabitEditorAction.Goal("g1"), HabitEditorAction.Gentle(false), HabitEditorAction.Save,
        )
        val saved = h.habits.single()
        assertEquals("Evening stretch", saved.name)
        assertEquals("activity", saved.icon)
        assertEquals(HabitType.Duration, saved.type)
        assertEquals(15, saved.target)
        assertEquals("minutes", saved.unit)
        assertEquals(TimeOfDay.Evening, saved.timeOfDay)
        assertEquals(WEEKDAYS, saved.days)
        assertEquals("07:15", saved.reminder)
        assertEquals("g1", saved.goalId)
        assertFalse(saved.gentle)
        assertFalse(h.form.open)
        assertEquals(listOf<UiEvent<HabitEditorAction>>(UiEvent.Snack("Habit created · Evening stretch")), h.events)
        assertEquals(listOf<HabitEditorEvent>(HabitEditorEvent.Created(saved.id)), h.nav)
    }

    @Test fun typeSwitchSetsUnitAndBinarySavesTargetOne() = runTest {
        val h = start()
        h.act(HabitEditorAction.Open("Walk"), HabitEditorAction.Type(HabitType.Duration))
        assertEquals("minutes", h.form.unit)
        assertTrue(h.state.showTarget)
        h.act(HabitEditorAction.Type(HabitType.Count))
        assertEquals("times", h.form.unit)
        h.act(HabitEditorAction.Type(HabitType.Quantity))
        assertEquals("glasses", h.form.unit)
        h.act(HabitEditorAction.Target("8"), HabitEditorAction.Type(HabitType.Binary))
        assertEquals("", h.form.unit)
        h.act(HabitEditorAction.Save)
        val saved = h.habits.single()
        assertEquals(1, saved.target)
        assertEquals("", saved.unit)
    }

    @Test fun targetIsDigitsDefaultsToOneAndClamps() = runTest {
        val h = start()
        h.act(HabitEditorAction.Open("Water"), HabitEditorAction.Type(HabitType.Quantity), HabitEditorAction.Target("8a,"))
        assertEquals("8", h.form.target)
        h.act(HabitEditorAction.Target(""), HabitEditorAction.Save)
        assertEquals(1, h.habits.last().target)

        h.act(HabitEditorAction.Open("Steps"), HabitEditorAction.Type(HabitType.Count), HabitEditorAction.Target("9999999"), HabitEditorAction.Save)
        assertEquals(100_000, h.habits.last().target)
    }

    @Test fun scheduleLabelFollowsDays() = runTest {
        val h = start()
        h.act(HabitEditorAction.Open("Read"))
        assertEquals("Every day", h.state.scheduleLabel)
        h.act(HabitEditorAction.ToggleDay(5)) // Sat off
        assertEquals("Custom", h.state.scheduleLabel)
        h.act(HabitEditorAction.ToggleDay(6)) // Sun off → Mon–Fri
        assertEquals("Weekdays", h.state.scheduleLabel)
        h.act(HabitEditorAction.Schedule("3× a week"))
        assertEquals(listOf(true, false, true, false, true, false, false), h.form.days)
        assertEquals("3× a week", h.state.scheduleLabel)
        h.act(HabitEditorAction.Schedule("Every day"))
        assertEquals("Every day", h.state.scheduleLabel)
        h.act(HabitEditorAction.Schedule("Custom")) // a label, not a preset
        assertEquals("Every day", h.state.scheduleLabel)
    }

    @Test fun editPrefillsFromHabit() = runTest {
        val water = testHabit("water", "Water", type = HabitType.Quantity, target = 8, unit = "glasses", reminder = "08:00", goalId = "g1", icon = "droplet")
        val h = start(habits = listOf(water), goals = listOf(testGoal("g1", "Hydrate", GoalState.Paused)))
        h.act(HabitEditorAction.Edit("water"))
        assertEquals("Edit habit", h.state.heading)
        assertEquals("Save", h.state.saveLabel)
        assertEquals("water", h.form.editId)
        assertEquals("8", h.form.target)
        assertEquals("glasses", h.form.unit)
        assertTrue(h.form.reminderOn)
        assertEquals("08:00", h.form.reminder)
        assertEquals("g1", h.form.goalId)
        assertEquals("droplet", h.form.icon)
        // A linked goal that is no longer active stays selectable, so the picker never misreports the link.
        assertEquals(listOf(null, "g1"), h.state.goals.map { it.id })
    }

    @Test fun editSendsOnlyChangedFields() = runTest {
        val h = start(habits = listOf(testHabit("walk", "Morning walk", timeOfDay = TimeOfDay.Morning)))
        h.act(HabitEditorAction.Edit("walk"), HabitEditorAction.Name("Walk"), HabitEditorAction.Save)
        assertEquals(listOf("walk" to listOf<HabitChange>(HabitChange.Name("Walk"))), h.repo.updates)
        assertEquals(listOf<UiEvent<HabitEditorAction>>(UiEvent.Snack("Habit updated")), h.events)
        assertTrue(h.nav.isEmpty())
        assertFalse(h.form.open)
    }

    @Test fun editReminderOffAndGoalClearSendNulls() = runTest {
        val h = start(habits = listOf(testHabit("walk", reminder = "07:00", goalId = "g1")))
        h.act(HabitEditorAction.Edit("walk"), HabitEditorAction.ReminderOn(false), HabitEditorAction.Goal(null), HabitEditorAction.Save)
        assertEquals(listOf<HabitChange>(HabitChange.Reminder(null), HabitChange.GoalId(null)), h.repo.updates.single().second)
    }

    @Test fun unchangedEditSendsNothing() = runTest {
        val h = start(habits = listOf(testHabit("walk")))
        h.act(HabitEditorAction.Edit("walk"), HabitEditorAction.Save)
        assertTrue(h.repo.updates.isEmpty())
        assertFalse(h.form.open)
    }

    @Test fun dismissClosesWithoutSaving() = runTest {
        val h = start()
        h.act(HabitEditorAction.Open("Read"), HabitEditorAction.Dismiss)
        assertFalse(h.form.open)
        assertTrue(h.habits.isEmpty())
    }
}
