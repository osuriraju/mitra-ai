package tech.beyondscale.mitra.feature.tasks

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
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeAuthRepository
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import tech.beyondscale.mitra.testing.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FocusViewModelTest : ViewModelTest() {
    // 04:30Z = 10:00 on Tue 13 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"), TimeZone.of("Asia/Kolkata"))
    private val a = Task("a", "A", due = "2026-10-13", estMin = 45, pri = Priority.High)
    private val b = Task("b", "Email Ravi", due = "2026-10-13", estMin = 30, pri = Priority.Med)
    private val c = Task("c", "Stretch", due = "2026-10-13")
    private val later = Task("l", "Plan trip", due = "2026-10-20")

    private class Harness(val vm: FocusViewModel, val events: List<UiEvent<FocusAction>>, val opened: List<String>)

    private fun TestScope.start(repo: FakeTasksRepository, taskId: String = "", focusMinutes: Int = 25): Harness {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val auth = FakeAuthRepository(SessionState.SignedIn(testUser(settings = Settings(focusMinutes = focusMinutes))))
        val vm = FocusViewModel(taskId, repo, auth, clock)
        val events = mutableListOf<UiEvent<FocusAction>>()
        val opened = mutableListOf<String>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        backgroundScope.launch(d) { vm.openTask.toList(opened) }
        runCurrent()
        return Harness(vm, events, opened)
    }

    @Test fun startsWithEstimateOrFocusMinutes() = runTest {
        val s = start(FakeTasksRepository(listOf(a, b, c))).vm.state.value
        assertFalse(s.empty)
        assertEquals("A", s.title)
        assertEquals("45:00", s.clock)
        assertTrue(s.running)
        assertEquals("1 of 3 · Today", s.position)
        assertEquals("Next: Email Ravi · 30m · ", s.next)
        assertEquals(0, s.segments)

        val s2 = start(FakeTasksRepository(listOf(c))).vm.state.value
        assertEquals("25:00", s2.clock)
        assertNull(s2.next)
    }

    @Test fun countsDownOnlyWhileRunning() = runTest {
        val h = start(FakeTasksRepository(listOf(c)))
        advanceTimeBy(1_001)
        assertEquals("24:59", h.vm.state.value.clock)
        advanceTimeBy(60_000)
        assertEquals("23:59", h.vm.state.value.clock)
        h.vm.onAction(FocusAction.PauseResume)
        runCurrent()
        assertFalse(h.vm.state.value.running)
        advanceTimeBy(10_000)
        assertEquals("23:59", h.vm.state.value.clock)
        h.vm.onAction(FocusAction.PauseResume)
        advanceTimeBy(1_001)
        assertTrue(h.vm.state.value.running)
        assertEquals("23:58", h.vm.state.value.clock)
    }

    @Test fun reachingZeroEndsSession() = runTest {
        val h = start(FakeTasksRepository(listOf(c.copy(estMin = 1, pri = Priority.High), b)))
        advanceTimeBy(60_001)
        val s = h.vm.state.value
        assertFalse(s.running)
        assertTrue(s.finished)
        assertEquals("00:00", s.clock)
        assertEquals(4, s.segments)
        assertTrue("1 session" in s.position, s.position)
        assertEquals(listOf(UiEvent.Snack<FocusAction>("Session complete — take a short break"), UiEvent.Haptic(HapticKind.Confirm)), h.events)
        advanceTimeBy(5_000)
        assertEquals("00:00", h.vm.state.value.clock)
    }

    @Test fun segmentsFillByQuarter() = runTest {
        val h = start(FakeTasksRepository(listOf(c.copy(estMin = 4))))
        advanceTimeBy(59_001)
        assertEquals(0, h.vm.state.value.segments)
        advanceTimeBy(1_000)
        assertEquals(1, h.vm.state.value.segments)
        advanceTimeBy(120_000)
        assertEquals(3, h.vm.state.value.segments)
    }

    @Test fun doneLogsMinutesAndAdvances() = runTest {
        val repo = FakeTasksRepository(listOf(a, b, c))
        val h = start(repo)
        advanceTimeBy(120_001)
        h.vm.onAction(FocusAction.Done)
        runCurrent()
        assertEquals(listOf("done:a:true"), repo.calls)
        assertEquals(listOf(UiEvent.Snack<FocusAction>("Completed · A · 2 min logged"), UiEvent.Haptic(HapticKind.Confirm)), h.events)
        val s = h.vm.state.value
        assertEquals("Email Ravi", s.title)
        assertEquals("30:00", s.clock)
        assertTrue(s.running)
        assertEquals("1 of 2 · Today", s.position)
    }

    @Test fun doneOnLastClosesScreen() = runTest {
        val repo = FakeTasksRepository(listOf(c))
        val h = start(repo)
        h.vm.onAction(FocusAction.Done)
        runCurrent()
        assertEquals(listOf("done:c:true"), repo.calls)
        assertEquals(UiEvent.Close, h.events.last())
        assertTrue(h.vm.state.value.empty)
    }

    @Test fun pinnedTaskFirst() = runTest {
        val h = start(FakeTasksRepository(listOf(a, b, later)), taskId = "l")
        val s = h.vm.state.value
        assertEquals("Plan trip", s.title)
        assertEquals("25:00", s.clock)
        assertEquals("1 of 3 · Today", s.position)
        assertEquals("Next: A · 45m · ", s.next)
    }

    @Test fun skipPastEndCloses() = runTest {
        val repo = FakeTasksRepository(listOf(a, b))
        val h = start(repo)
        h.vm.onAction(FocusAction.Skip)
        runCurrent()
        assertEquals("Email Ravi", h.vm.state.value.title)
        assertEquals("2 of 2 · Today", h.vm.state.value.position)
        assertEquals("30:00", h.vm.state.value.clock)
        assertEquals(emptyList(), h.events)
        h.vm.onAction(FocusAction.Skip)
        runCurrent()
        assertEquals(listOf(UiEvent.Snack<FocusAction>("That was the last task for today"), UiEvent.Close), h.events)
        assertEquals(emptyList(), repo.calls)
    }

    @Test fun resetAddFiveAndOpenTask() = runTest {
        val h = start(FakeTasksRepository(listOf(c)))
        advanceTimeBy(10_001)
        assertEquals("24:50", h.vm.state.value.clock)
        h.vm.onAction(FocusAction.AddFive)
        runCurrent()
        assertEquals("29:50", h.vm.state.value.clock)
        h.vm.onAction(FocusAction.PauseResume)
        h.vm.onAction(FocusAction.Reset)
        runCurrent()
        assertEquals("25:00", h.vm.state.value.clock)
        assertTrue(h.vm.state.value.running)
        h.vm.onAction(FocusAction.OpenTask)
        runCurrent()
        assertEquals(listOf("c"), h.opened)
    }

    @Test fun emptyQueue() = runTest {
        val s = start(FakeTasksRepository(listOf(later))).vm.state.value
        assertTrue(s.empty)
        assertNull(s.title)
        assertFalse(s.running)
    }
}
