package tech.beyondscale.mitra.feature.tasks

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskStatus
import tech.beyondscale.mitra.domain.tasks.TaskCounts
import tech.beyondscale.mitra.domain.tasks.TaskFilters
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest : ViewModelTest() {
    // 04:30Z = 10:00 on Tue 13 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"), TimeZone.of("Asia/Kolkata"))
    private val project = Project("p1", "Mitra", "🚀")

    private val inbox = Task("i1", "Call bank")
    private val overdue = Task("o1", "Pay rent", due = "2026-10-11", projectId = "p1")
    private val report = Task("t1", "Write report", due = "2026-10-13", pri = Priority.High, estMin = 60)
    private val upcoming = Task("u1", "Plan trip", due = "2026-10-15", projectId = "p1")

    private fun fixtures() = listOf(inbox, overdue, report, upcoming)

    private class Harness(val vm: TasksViewModel, val events: List<UiEvent<TasksAction>>)

    private fun TestScope.start(repo: FakeTasksRepository): Harness {
        val vm = TasksViewModel(repo, clock)
        val events = mutableListOf<UiEvent<TasksAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, events)
    }

    @Test fun buildsTabsFromSelectors() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project))
        val h = start(repo)
        val s = h.vm.state.value
        assertEquals(TaskCounts(1, 2, 1, 1), s.counts)
        assertEquals("2026-10-13", s.today)
        assertEquals(TasksTab.Today, s.tab)
        assertEquals(listOf("i1"), s.inbox.map { it.id })
        assertEquals(listOf("o1"), s.overdue.map { it.id })
        assertEquals(listOf("u1"), s.upcoming.flatMap { g -> g.rows.map { it.id } })
        assertEquals(listOf(ProjectCardUi("p1", "🚀", "Mitra", open = 2, pct = 0)), s.projects)

        val done = Task("t2", "Stretch", due = "2026-10-13", done = true, status = TaskStatus.Done, completedAt = "2026-10-13", estMin = 30)
        val morning = Task("t3", "Standup", due = "2026-10-13", time = "09:00")
        repo.taskFlow.value = fixtures() + done + morning
        runCurrent()
        val s2 = h.vm.state.value
        assertEquals(listOf("t3", "t1", "t2"), s2.todayRows.map { it.id })
        assertEquals(2, s2.openCount)
        assertEquals(1, s2.doneCount)
        assertEquals("1h 30m", s2.planned)
        assertEquals("30m", s2.doneTime)
        assertEquals(1f / 3f, s2.plannedFraction)

        h.vm.onAction(TasksAction.SelectTab(TasksTab.Upcoming))
        assertEquals(TasksTab.Upcoming, h.vm.state.value.tab)
    }

    @Test fun completeEmitsHapticAndUndoableSnack() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project))
        val h = start(repo)
        h.vm.onAction(TasksAction.Toggle("t1"))
        runCurrent()
        assertEquals(listOf("done:t1:true"), repo.calls)
        val undo = TasksAction.UndoComplete("t1", null)
        assertEquals(listOf(UiEvent.Haptic(HapticKind.Confirm), UiEvent.Snack("Completed · Write report", undo)), h.events)

        h.vm.onAction(undo)
        runCurrent()
        assertEquals(listOf("done:t1:true", "done:t1:false"), repo.calls)
    }

    @Test fun reopeningADoneTaskShowsNoSnack() = runTest {
        val repo = FakeTasksRepository(listOf(report.copy(done = true, status = TaskStatus.Done, completedAt = "2026-10-13")))
        val h = start(repo)
        h.vm.onAction(TasksAction.Toggle("t1"))
        runCurrent()
        assertEquals(listOf("done:t1:false"), repo.calls)
        assertEquals(emptyList(), h.events)
    }

    @Test fun undoCompleteDeletesSpawnedOccurrence() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project)).apply { spawnOnComplete = "next1" }
        val h = start(repo)
        h.vm.onAction(TasksAction.Toggle("t1"))
        runCurrent()
        val snack = h.events.filterIsInstance<UiEvent.Snack<TasksAction>>().single()
        assertEquals(TasksAction.UndoComplete("t1", "next1"), snack.undo)
        h.vm.onAction(snack.undo!!)
        runCurrent()
        assertEquals(listOf("done:t1:true", "done:t1:false", "delete:next1"), repo.calls)
    }

    @Test fun swipeLeftMovesToTomorrowWithUndo() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project))
        val h = start(repo)
        h.vm.onAction(TasksAction.Reschedule("t1"))
        runCurrent()
        assertEquals(listOf("update:t1:Due(value=2026-10-14)"), repo.calls)
        val snack = h.events.single() as UiEvent.Snack<TasksAction>
        assertEquals("Moved to tomorrow · Write report", snack.text)
        h.vm.onAction(snack.undo!!)
        runCurrent()
        assertEquals("update:t1:Due(value=2026-10-13)", repo.calls.last())
    }

    @Test fun moveOverdueToTodayRestoresOnUndo() = runTest {
        val second = Task("o2", "Renew passport", due = "2026-10-12")
        val repo = FakeTasksRepository(fixtures() + second, listOf(project))
        val h = start(repo)
        h.vm.onAction(TasksAction.MoveOverdueToToday)
        runCurrent()
        assertEquals(setOf("update:o1:Due(value=2026-10-13)", "update:o2:Due(value=2026-10-13)"), repo.calls.toSet())
        val snack = h.events.single() as UiEvent.Snack<TasksAction>
        assertEquals("2 overdue tasks moved to today", snack.text)
        assertEquals(emptyList(), h.vm.state.value.overdue)

        repo.calls.clear()
        h.vm.onAction(snack.undo!!)
        runCurrent()
        assertEquals(setOf("update:o1:Due(value=2026-10-11)", "update:o2:Due(value=2026-10-12)"), repo.calls.toSet())
        assertEquals(listOf("o1", "o2"), h.vm.state.value.overdue.map { it.id })
    }

    @Test fun addToInboxIgnoresBlankAndOffersUndo() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project))
        val h = start(repo)
        h.vm.onAction(TasksAction.AddToInbox("   "))
        runCurrent()
        assertEquals(emptyList(), repo.calls)
        assertEquals(emptyList(), h.events)

        h.vm.onAction(TasksAction.AddToInbox("Buy milk"))
        runCurrent()
        assertEquals(listOf("create:Buy milk"), repo.calls)
        val snack = h.events.single() as UiEvent.Snack<TasksAction>
        assertEquals("Added to inbox · Buy milk", snack.text)
        h.vm.onAction(snack.undo!!)
        runCurrent()
        assertEquals("delete:new1", repo.calls.last())
    }

    @Test fun triageUpdatesAndUndoClearsPlacement() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project))
        val h = start(repo)
        assertEquals(listOf(project), h.vm.state.value.triageProjects)
        h.vm.onAction(TasksAction.Triage("i1", tech.beyondscale.mitra.domain.model.TaskChange.ProjectId("p1"), "Mitra"))
        runCurrent()
        assertEquals(listOf("update:i1:ProjectId(value=p1)"), repo.calls)
        val snack = h.events.single() as UiEvent.Snack<TasksAction>
        assertEquals("Call bank → Mitra", snack.text)
        h.vm.onAction(snack.undo!!)
        runCurrent()
        assertEquals("update:i1:ProjectId(value=null),Due(value=null),Someday(value=false)", repo.calls.last())
    }

    @Test fun filtersApplyToTodayAndUpcoming() = runTest {
        val extra = listOf(
            Task("t2", "Low today", due = "2026-10-13", pri = Priority.Low),
            Task("o2", "High overdue", due = "2026-10-10", pri = Priority.High),
            Task("u2", "High later", due = "2026-10-16", pri = Priority.High),
        )
        val repo = FakeTasksRepository(fixtures() + extra, listOf(project))
        val h = start(repo)
        h.vm.onAction(TasksAction.SetFilters(TaskFilters(pri = Priority.High)))
        runCurrent()
        val s = h.vm.state.value
        assertEquals(TaskFilters(pri = Priority.High), s.filters)
        assertEquals(listOf("t1"), s.todayRows.map { it.id })
        assertEquals(listOf("o2"), s.overdue.map { it.id })
        assertEquals(listOf("u2"), s.upcoming.flatMap { g -> g.rows.map { it.id } })
        // Counts on the tabs stay unfiltered, like the web.
        assertEquals(TaskCounts(1, 4, 2, 1), s.counts)
    }

    @Test fun refreshOfflineShowsMessage() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project)).apply { refreshResult = Outcome.Err(AppError.Offline) }
        val h = start(repo)
        h.vm.onAction(TasksAction.Refresh)
        runCurrent()
        assertEquals(listOf("refresh"), repo.calls)
        assertEquals(listOf<UiEvent<TasksAction>>(UiEvent.Snack("You're offline — showing tasks saved on this phone")), h.events)
        assertFalse(h.vm.state.value.refreshing)
    }

    @Test fun refreshServerErrorShowsItsMessage() = runTest {
        val repo = FakeTasksRepository(fixtures()).apply { refreshResult = Outcome.Err(AppError.Server(503, "UNAVAILABLE", "Server is busy")) }
        val h = start(repo)
        h.vm.onAction(TasksAction.Refresh)
        runCurrent()
        assertEquals(listOf<UiEvent<TasksAction>>(UiEvent.Snack("Server is busy")), h.events)
    }

    @Test fun toggleShowDoneFlipsFlag() = runTest {
        val h = start(FakeTasksRepository(fixtures()))
        h.vm.onAction(TasksAction.ToggleShowDone)
        assertFalse(h.vm.state.value.showDone)
    }

    @Test fun refreshSupersededIsNotAnError() = runTest {
        val superseded = AppError.Server(409, tech.beyondscale.mitra.domain.repository.TasksRepository.REFRESH_SUPERSEDED, "Refresh superseded")
        val repo = FakeTasksRepository(fixtures()).apply { refreshResult = Outcome.Err(superseded) }
        val h = start(repo)
        h.vm.onAction(TasksAction.Refresh)
        runCurrent()
        assertEquals(listOf("refresh"), repo.calls)
        assertEquals(emptyList(), h.events)
        assertFalse(h.vm.state.value.refreshing)
    }

    @Test fun createProjectAnnounces() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project))
        val h = start(repo)
        h.vm.onAction(TasksAction.CreateProject("  Work ", "💼"))
        runCurrent()
        assertEquals(listOf("createProject:Work"), repo.calls)
        assertEquals(listOf<UiEvent<TasksAction>>(UiEvent.Snack("Project created · Work")), h.events)
        assertEquals(listOf("p1", "proj1"), h.vm.state.value.projects.map { it.id })
        assertEquals("💼", h.vm.state.value.projects.last().emoji)

        h.vm.onAction(TasksAction.CreateProject("   ", "🚀"))
        runCurrent()
        assertEquals(listOf("createProject:Work"), repo.calls)
    }

    private fun TestScope.selecting(repo: FakeTasksRepository, vararg ids: String): Harness {
        val h = start(repo)
        h.vm.onAction(TasksAction.LongPress(ids.first()))
        ids.drop(1).forEach { h.vm.onAction(TasksAction.ToggleSelect(it)) }
        runCurrent()
        return h
    }

    private fun two() = listOf(Task("a", "A", projectId = "p1"), Task("b", "B"))

    @Test fun longPressStartsSelectionWithHaptic() = runTest {
        val h = start(FakeTasksRepository(two(), listOf(project)))
        h.vm.onAction(TasksAction.LongPress("a"))
        runCurrent()
        assertEquals(setOf("a"), h.vm.state.value.selection)
        assertEquals(listOf<UiEvent<TasksAction>>(UiEvent.Haptic(HapticKind.LongPress)), h.events)
    }

    @Test fun tapTogglesWhileSelecting() = runTest {
        val h = start(FakeTasksRepository(two(), listOf(project)))
        h.vm.onAction(TasksAction.LongPress("a"))
        h.vm.onAction(TasksAction.ToggleSelect("b"))
        h.vm.onAction(TasksAction.ToggleSelect("a"))
        runCurrent()
        assertEquals(setOf("b"), h.vm.state.value.selection)
        h.vm.onAction(TasksAction.ClearSelection)
        assertEquals(emptySet(), h.vm.state.value.selection)
    }

    @Test fun bulkCompleteWithUndo() = runTest {
        val repo = FakeTasksRepository(two(), listOf(project))
        val h = selecting(repo, "a", "b")
        h.vm.onAction(TasksAction.BulkComplete)
        runCurrent()
        assertEquals(listOf("done:a:true", "done:b:true"), repo.calls)
        assertEquals(emptySet(), h.vm.state.value.selection)
        val snack = h.events.filterIsInstance<UiEvent.Snack<TasksAction>>().single()
        assertEquals("2 tasks completed", snack.text)
        h.vm.onAction(snack.undo!!)
        runCurrent()
        assertEquals(listOf("done:a:true", "done:b:true", "done:a:false", "done:b:false"), repo.calls)
    }

    @Test fun bulkMoveRestoresOriginalProjects() = runTest {
        val repo = FakeTasksRepository(two(), listOf(project))
        val h = selecting(repo, "a", "b")
        h.vm.onAction(TasksAction.BulkMove("p1"))
        runCurrent()
        assertEquals(listOf("update:a:ProjectId(value=p1)", "update:b:ProjectId(value=p1)"), repo.calls)
        assertEquals(emptySet(), h.vm.state.value.selection)
        val snack = h.events.filterIsInstance<UiEvent.Snack<TasksAction>>().single()
        assertEquals("Moved 2 tasks to Mitra", snack.text)
        h.vm.onAction(snack.undo!!)
        runCurrent()
        assertEquals(listOf("p1", null), repo.taskFlow.value.map { it.projectId })
    }

    @Test fun bulkDeleteRestoresAll() = runTest {
        val repo = FakeTasksRepository(two(), listOf(project))
        val h = selecting(repo, "a", "b")
        h.vm.onAction(TasksAction.BulkDelete)
        runCurrent()
        assertEquals(listOf("delete:a", "delete:b"), repo.calls)
        assertEquals(HapticKind.Reject, h.events.filterIsInstance<UiEvent.Haptic>().last().kind)
        val snack = h.events.filterIsInstance<UiEvent.Snack<TasksAction>>().single()
        assertEquals("2 tasks deleted", snack.text)
        h.vm.onAction(snack.undo!!)
        runCurrent()
        assertEquals(listOf("delete:a", "delete:b", "restore:a,b"), repo.calls)
    }

    @Test fun switchingTabClearsSelection() = runTest {
        val h = selecting(FakeTasksRepository(two(), listOf(project)), "a")
        h.vm.onAction(TasksAction.SelectTab(TasksTab.Inbox))
        runCurrent()
        assertEquals(emptySet(), h.vm.state.value.selection)
    }
}
