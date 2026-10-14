package tech.beyondscale.mitra.feature.tasks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskStatus
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeGoalsRepository
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModelTest : ViewModelTest() {
    // 04:30Z = 10:00 on Tue 13 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-13T04:30:00Z"), TimeZone.of("Asia/Kolkata"))
    private val project = Project("p1", "Mitra", "🚀")
    private val other = Project("p2", "Home", "🏠")
    private val shipped = Task("t1", "Ship", projectId = "p1", done = true, status = TaskStatus.Done, completedAt = "2026-10-12")
    private val write = Task("t2", "Write docs", projectId = "p1", due = "2026-10-13")
    private val test = Task("t3", "Test", projectId = "p1")
    private val elsewhere = Task("t4", "Groceries", projectId = "p2")

    private fun fixtures() = listOf(shipped, write, test, elsewhere)

    private class Harness(val vm: ProjectDetailViewModel, val events: List<UiEvent<ProjectDetailAction>>)

    private fun TestScope.start(repo: FakeTasksRepository, id: String = "p1", goals: FakeGoalsRepository = FakeGoalsRepository(clock = clock)): Harness {
        val vm = ProjectDetailViewModel(id, repo, goals, clock)
        val events = mutableListOf<UiEvent<ProjectDetailAction>>()
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        backgroundScope.launch(d) { vm.events.toList(events) }
        runCurrent()
        return Harness(vm, events)
    }

    @Test fun statsAndOrder() = runTest {
        val s = start(FakeTasksRepository(fixtures(), listOf(project, other))).vm.state.value
        assertTrue(s.loaded)
        assertFalse(s.notFound)
        assertEquals("Mitra", s.name)
        assertEquals("🚀", s.emoji)
        assertEquals(2, s.open)
        assertEquals(1, s.done)
        assertEquals(33, s.pct)
        assertEquals(listOf("t2", "t3", "t1"), s.rows.map { it.id })
    }

    @Test fun editRenames() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project, other))
        val h = start(repo)
        h.vm.onAction(ProjectDetailAction.Edit("  Home  ", "🏠", null))
        runCurrent()
        assertEquals(listOf("updateProject:p1:Home:🏠"), repo.calls)
        assertEquals("Home", h.vm.state.value.name)
        assertEquals(listOf(UiEvent.Snack<ProjectDetailAction>("Project updated")), h.events)
    }

    @Test fun deleteNeverReportsNotFound() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project, other))
        val h = start(repo)
        val seen = mutableListOf<ProjectDetailUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.vm.state.toList(seen) }
        runCurrent()
        h.vm.onAction(ProjectDetailAction.Delete)
        runCurrent()
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.none { it.notFound }, "not-found flashed: $seen")
    }

    @Test fun doubleEditUpdatesOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeTasksRepository(fixtures(), listOf(project, other)).apply { projectGate = gate }
        val h = start(repo)
        h.vm.onAction(ProjectDetailAction.Edit("Home", "🏠", null))
        h.vm.onAction(ProjectDetailAction.Edit("Home", "🏠", null))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("updateProject:p1:Home:🏠"), repo.calls)
    }

    @Test fun editWithBlankNameIsIgnored() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project))
        val h = start(repo)
        h.vm.onAction(ProjectDetailAction.Edit("  ", "🏠", null))
        runCurrent()
        assertEquals(emptyList(), repo.calls)
    }

    @Test fun deleteMovesTasksToInboxAndCloses() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project, other))
        val h = start(repo)
        h.vm.onAction(ProjectDetailAction.Delete)
        runCurrent()
        assertEquals(listOf("deleteProject:p1"), repo.calls)
        assertEquals(listOf(UiEvent.Snack<ProjectDetailAction>("Project deleted"), UiEvent.Close), h.events)
        assertTrue(repo.taskFlow.value.filter { it.id in setOf("t1", "t2", "t3") }.all { it.projectId == null })
    }

    @Test fun toggleCompletesWithUndo() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project)).apply { spawnOnComplete = "next1" }
        val h = start(repo)
        h.vm.onAction(ProjectDetailAction.Toggle("t2"))
        runCurrent()
        val undo = ProjectDetailAction.UndoComplete("t2", "next1")
        assertEquals(listOf(UiEvent.Haptic(HapticKind.Confirm), UiEvent.Snack("Completed · Write docs", undo)), h.events)
        assertEquals(listOf("t3", "t1", "t2").toSet(), h.vm.state.value.rows.map { it.id }.toSet())
        assertEquals("t3", h.vm.state.value.rows.first().id)
        h.vm.onAction(undo)
        runCurrent()
        assertEquals(listOf("done:t2:true", "done:t2:false", "delete:next1"), repo.calls)
    }

    @Test fun toggleReopensDoneTaskQuietly() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project))
        val h = start(repo)
        h.vm.onAction(ProjectDetailAction.Toggle("t1"))
        runCurrent()
        assertEquals(listOf("done:t1:false"), repo.calls)
        assertEquals(emptyList(), h.events)
    }

    @Test fun swipeLeftMovesToTomorrowWithUndo() = runTest {
        val repo = FakeTasksRepository(fixtures(), listOf(project))
        val h = start(repo)
        h.vm.onAction(ProjectDetailAction.Reschedule("t2"))
        runCurrent()
        assertEquals(listOf("update:t2:Due(value=2026-10-14)"), repo.calls)
        val snack = h.events.single() as UiEvent.Snack<ProjectDetailAction>
        assertEquals("Moved to tomorrow · Write docs", snack.text)
        h.vm.onAction(snack.undo!!)
        runCurrent()
        assertEquals("update:t2:Due(value=2026-10-13)", repo.calls.last())
    }

    @Test fun missingProjectIsNotFound() = runTest {
        val s = start(FakeTasksRepository(fixtures(), listOf(project)), "nope").vm.state.value
        assertTrue(s.loaded)
        assertTrue(s.notFound)
    }

    @Test fun showsGoalAndDueChips() = runTest {
        val goals = FakeGoalsRepository(
            listOf(linkGoal("g1", "Ship Mitra v1", due = "2027-04-12"), linkGoal("g2", "Read 12 books", GoalState.Completed)),
            clock,
        )
        val repo = FakeTasksRepository(fixtures(), listOf(project.copy(goalId = "g1"), other.copy(goalId = "gone")))
        val s = start(repo, goals = goals).vm.state.value
        assertEquals(GoalChip("g1", "Ship Mitra v1", "2027-04-12"), s.goal)
        assertEquals("Due Mon 12 Apr", s.goalDueLabel) // web fmtDay(due, { relative: false }) keeps the weekday
        assertEquals("g1", s.goalId)
        // Project form: None + all goals, any state (web ProjectForm).
        assertEquals(listOf("g1" to "Ship Mitra v1", "g2" to "Read 12 books"), s.goalOptions)
        val unknown = start(repo, "p2", goals).vm.state.value
        assertEquals(null, unknown.goal)
        assertEquals(null, unknown.goalDueLabel)
    }

    @Test fun editChangesTheGoal() = runTest {
        val goals = FakeGoalsRepository(listOf(linkGoal("g1", "Ship Mitra v1")), clock)
        val repo = FakeTasksRepository(fixtures(), listOf(project.copy(goalId = "g1"), other))
        val h = start(repo, goals = goals)
        h.vm.onAction(ProjectDetailAction.Edit("Mitra", "🚀", null))
        runCurrent()
        assertEquals(null, repo.projectFlow.value.first { it.id == "p1" }.goalId)
        assertEquals(null, h.vm.state.value.goal)
    }
}
